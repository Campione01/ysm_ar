package ysmar.takeover;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import ysmar.Text;
import ysmar.YsmArConfig;
import ysmar.YsmArStats;
import ysmar.YsmArSubmitter;
import ysmar.core.Attributes;
import ysmar.core.NativeLoader;
import ysmar.takeover.TakeoverStats.Fallback;
import ysmar.takeover.Windows.Decision;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Front-end for the real Yes Steve Model 2.6.5: draws the body of its models through Accelerated Rendering and has
 * 2.6.5 skip its own draw of that body. 2.6.5 still animates the model and draws everything else (layers, held
 * items, name tag).
 *
 * How one entity render goes. 2.6.5 posts RenderLivingEvent.Pre from its renderer; the listener here notes renderer,
 * entity, pose stack, buffer source and light (the "window"). 2.6.5 then updates the animation, sets the pose up and
 * calls the vanilla LivingEntityRenderer.isBodyVisible, where a mixin of this mod asks hideBody(). There everything
 * that can refuse is done (DECIDE): if this mod has the model and Accelerated Rendering can take all of it, the
 * answer is "not visible", for which 2.6.5 selects no render type and draws no body. After that test 2.6.5 runs its
 * pre-render step, which scales the pose stack by the model scale, then its layers. Code of other mods can sit
 * before that step and move the pose or set bone values, so the body is drawn (EMIT) when that scale call returns,
 * where a second mixin calls onScale(): with the pose and the bone values 2.6.5's own draw would have used.
 * RenderLivingEvent.Post ends the window. Windows keeps the rule that a hidden body is drawn exactly once.
 *
 * Whatever is not as expected leaves the answer of the vanilla method alone, and 2.6.5 draws as it always does.
 * Render thread only.
 */
public final class Takeover {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final int UNKNOWN = 0;
    private static final int ACTIVE = 1;
    private static final int OFF = 2;
    private static final int MAX_RENDERER_CLASSES = 8;
    private static final int IMMEDIATE = -1;

    /** One run of a 2.6.5 renderer for one entity, with what DECIDE keeps for EMIT. */
    private static final class Render extends Windows.Window {
        MultiBufferSource buffers;
        int light;
        YsmHandles members;
        /** The texture a listener of the player texture event set for this render, or null. */
        ResourceLocation eventTexture;
        Object animatable;
        Object animated;
        Bindings.Binding binding;
        LateRead.Armed draw;
        boolean sideBySide;
    }

    private static final Windows WINDOWS = new Windows(new Windows.Stage() {
        @Override
        public Decision decide(Windows.Window window) throws Exception {
            return Takeover.decide((Render) window);
        }

        @Override
        public void emit(Windows.Window window, int point, Object lateStack) throws Exception {
            Takeover.emit((Render) window, point, lateStack);
        }

        @Override
        public void finished(Windows.Window window) {
            if (window.timed) {
                TakeoverStats.rendered(window.drawn && !window.shown, System.nanoTime() - window.startNanos);
            }
        }

        @Override
        public void lost(Windows.Window window) {
            Takeover.lost();
        }

        @Override
        public void failed(Throwable problem) {
            switchOff(problem);
        }
    });

    private static int activation = UNKNOWN;
    private static String offReason;
    private static YsmHandles handles;
    private static Bindings bindings;
    private static long windowsOpened;
    private static boolean hookMissed;

    /** How many of the two test calls of PoseStack.scale reached onScale: 2 when the late hook is in place. */
    private static int lateHookAnswers;
    private static PoseStack probe;
    private static int probeHits;
    private static boolean missedLogged;
    private static boolean lostLogged;
    private static boolean emitFailureLogged;

    private static boolean textureEventHeard;
    private static Object eventEntity;
    private static ResourceLocation eventTexture;

    private static final Class<?>[] RENDERER_CLASSES = new Class<?>[MAX_RENDERER_CLASSES];
    private static final List<String> FOREIGN_MIXINS = new ArrayList<>();
    private static int rendererClasses;

    private Takeover() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void init() {
        // A window never outlives the render it was opened for: every later Pre event closes it, also one that
        // another listener has cancelled; so does the next frame.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, RenderLivingEvent.Pre.class, event -> WINDOWS.closeAll());
        // Lowest priority and not for cancelled events: a render another mod cancels is not looked at.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderLivingEvent.Pre.class,
                event -> onPre((RenderLivingEvent.Pre<?, ?>) event));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderLivingEvent.Post.class,
                event -> onPost((RenderLivingEvent.Post<?, ?>) event));
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, event -> onFrame());
    }

    /**
     * Called at the head of LivingEntityRenderer.isBodyVisible. True: this mod draws the body of this entity for
     * this render, the caller answers "not visible". False: the vanilla method answers.
     */
    public static boolean hideBody(Object renderer, LivingEntity entity) {
        return WINDOWS.hide(renderer, entity);
    }

    /** Called when a PoseStack.scale call returns while a render waits for the one of its pre-render step. */
    public static void onScale(Object stack, float x, float y, float z) {
        if (stack == probe) {
            probeHits++;
            return;
        }
        if (RenderSystem.isOnRenderThread()) {
            WINDOWS.scale(stack, x, y, z);
        }
    }

    /** Drops every binding and counter; the settings were re-read or the models dropped. */
    public static void reset() {
        WINDOWS.clear();
        WINDOWS.resetCounters();
        if (bindings != null) {
            bindings.clear();
        }
        TakeoverStats.reset();
        windowsOpened = 0;
        hookMissed = false;
        eventEntity = null;
        eventTexture = null;
    }

    /** Lines for /ysm_ar status: state of the front-end, the bindings, the counters. */
    public static List<String> status() {
        List<String> lines = new ArrayList<>();
        YsmArConfig config = YsmArConfig.current();
        String state;
        if (!config.enabled) {
            state = "off (enabled=false)";
        } else if (activation == OFF) {
            state = "OFF for this session: " + offReason;
        } else if (activation == UNKNOWN) {
            state = "not looked at yet (no living entity was drawn)";
        } else {
            state = (config.takeoverEnabled ? "active" : "switched off (takeover.enabled=false)") + " for yes_steve_model " + YsmHandles.VERSION
                    + ", maids " + (handles.maidAnimatable == null ? "no (no ysm_maid attachment)" : "yes")
                    + ", windows opened " + windowsOpened + ", isBodyVisible hook answered " + WINDOWS.hookCalls()
                    + (hookMissed ? " (THE HOOK IS NOT CALLED: the mixin is not applied)" : "");
        }
        lines.add("take-over: " + state);
        if (activation == ACTIVE) {
            lines.add("take-over members resolved: " + YsmHandles.REQUIRED + " required; optional: " + handles.describeOptional(textureEventHeard));
            lines.add("take-over late read: " + describeLateRead(config));
            lines.add("take-over, mixins of other mods on the renderer classes of Yes Steve Model: " + String.join("; ", FOREIGN_MIXINS));
        }
        if (bindings != null) {
            lines.addAll(bindings.describe());
        }
        lines.add("take-over since start or reload: " + TakeoverStats.total().describe());
        return lines;
    }

    private static String describeLateRead(YsmArConfig config) {
        boolean applied = lateHookAnswers == 2;
        if (applied && config.takeoverLateRead) {
            return "on, the hook on PoseStack.scale is applied (2 of 2 test calls answered): a body is drawn at the scale call of"
                    + " Yes Steve Model's pre-render step, with the bone values and the pose of that moment";
        }
        String where = "; bodies are drawn at the visibility test, as in 0.2.1";
        if (!config.takeoverLateRead) {
            return "off (takeover.late_read=false), the hook on PoseStack.scale is " + (applied ? "applied" : "not applied") + where;
        }
        return "NOT APPLIED: the hook on PoseStack.scale answered " + lateHookAnswers + " of 2 test calls (it is put in place when the"
                + " game starts, and only with takeover.late_read=true at that time)" + where;
    }

    private static boolean lateRead(YsmArConfig config) {
        return lateHookAnswers == 2 && config.takeoverLateRead;
    }

    private static void onFrame() {
        WINDOWS.frame();
        eventEntity = null;
        eventTexture = null;
    }

    private static void onPre(RenderLivingEvent.Pre<?, ?> event) {
        if (activation == OFF) {
            return;
        }
        YsmArConfig config = YsmArConfig.current();
        if (!config.enabled) {
            return;
        }
        try {
            if (activation == UNKNOWN && !activate()) {
                return;
            }
            LivingEntityRenderer<?, ?> renderer = event.getRenderer();
            // Without a usable Accelerated Rendering nothing can be taken over and nothing is counted.
            if (!handles.rendererBase.isInstance(renderer) || !RenderSystem.isOnRenderThread() || !YsmArSubmitter.usable()) {
                return;
            }
            noteRendererClass(renderer.getClass());
            LivingEntity entity = event.getEntity();
            boolean level = YsmArSubmitter.renderingLevel();
            Render window = new Render();
            window.renderer = renderer;
            window.entity = entity;
            window.stack = event.getPoseStack();
            window.buffers = event.getMultiBufferSource();
            window.light = event.getPackedLight();
            window.members = handles;
            if (eventEntity == entity) {
                window.eventTexture = eventTexture;
            }
            eventEntity = null;
            eventTexture = null;
            // Only renders of the level are timed and counted: previews and screens are never taken over.
            window.timed = level;
            if (!level) {
                TakeoverStats.preview();
            } else if (!YsmArSubmitter.ready()) {
                TakeoverStats.fallback(Fallback.AR_OFF);
            } else if (!config.takeoverEnabled) {
                TakeoverStats.fallback(Fallback.SWITCHED_OFF);
            } else if (Minecraft.getInstance().shouldEntityAppearGlowing(entity)) {
                // 2.6.5 draws a glowing entity's outline from the same vertices as its body.
                TakeoverStats.fallback(Fallback.GLOWING);
            } else {
                window.open = true;
                windowsOpened++;
                if (!hookMissed && windowsOpened > 600 && WINDOWS.hookCalls() == 0) {
                    hookMissed = true;
                    LOGGER.warn("ysm_ar: {} renders of Yes Steve Model were ready to be taken over but the isBodyVisible hook was never called;"
                            + " the mixin of this mod is not applied, nothing is taken over", windowsOpened);
                }
            }
            window.startNanos = System.nanoTime();
            WINDOWS.open(window);
        } catch (Throwable problem) {
            switchOff(problem);
            WINDOWS.closeAll();
        }
    }

    private static void onPost(RenderLivingEvent.Post<?, ?> event) {
        LivingEntityRenderer<?, ?> renderer = event.getRenderer();
        WINDOWS.post(renderer, event.getEntity(), activation == ACTIVE && handles.rendererBase.isInstance(renderer));
    }

    private static boolean activate() {
        try {
            handles = YsmHandles.resolve();
            bindings = new Bindings(FMLPaths.CONFIGDIR.get().resolve(YsmHandles.MOD_ID).resolve("custom"));
            lateHookAnswers = probeLateHook();
            textureEventHeard = listenForPlayerTextures(handles);
            activation = ACTIVE;
            LOGGER.info("ysm_ar: take-over front-end ready for yes_steve_model {}: {} required public members resolved by name and descriptor;"
                            + " optional: {}; maids {}", YsmHandles.VERSION, YsmHandles.REQUIRED, handles.describeOptional(textureEventHeard),
                    handles.maidAnimatable == null ? "not available (no ysm_maid attachment)" : "available");
            LOGGER.info("ysm_ar: take-over late read: {}", describeLateRead(YsmArConfig.current()));
            FOREIGN_MIXINS.clear();
            FOREIGN_MIXINS.add("renderer base: " + foreignMixins(handles.rendererBase));
            FOREIGN_MIXINS.add("renderer interface: " + (handles.rendererInterface == null ? "unknown" : foreignMixins(handles.rendererInterface)));
            LOGGER.info("ysm_ar: mixins of other mods on the renderer classes of Yes Steve Model: {}", String.join("; ", FOREIGN_MIXINS));
            return true;
        } catch (Members.Missing missing) {
            off(missing.getMessage());
        } catch (Throwable problem) {
            off(Text.clean(NativeLoader.describe(problem)));
        }
        return false;
    }

    /**
     * Two calls of PoseStack.scale on a stack of its own, one for each path through the vanilla method (a scale
     * that is the same on all axes, which is what most models have, and one that is not). Returns how many of them
     * reached onScale.
     */
    private static int probeLateHook() {
        PoseStack stack = new PoseStack();
        boolean before = LateHook.armed;
        probe = stack;
        probeHits = 0;
        LateHook.armed = true;
        try {
            stack.scale(1.0f, 1.0f, 1.0f);
            stack.scale(1.0f, 2.0f, 1.0f);
        } finally {
            LateHook.armed = before;
            probe = null;
        }
        return probeHits;
    }

    /** Told only: whatever goes wrong while Mixin is asked is "unknown" and changes nothing else. */
    private static String foreignMixins(Class<?> type) {
        try {
            return ForeignMixins.describe(type);
        } catch (Throwable unknown) {
            return "unknown";
        }
    }

    /** False when the event is not there or cannot be listened to: the check for a texture set by a listener is then absent. */
    private static boolean listenForPlayerTextures(YsmHandles members) {
        try {
            return TextureEventTap.register(NeoForge.EVENT_BUS, members.playerTextureEvent, members.eventPlayer, members.eventTexture,
                    Takeover::onPlayerTexture);
        } catch (Throwable absent) {
            return false;
        }
    }

    private static void onPlayerTexture(Object player, Object texture) {
        eventEntity = player;
        eventTexture = texture instanceof ResourceLocation location ? location : null;
    }

    /** The renderer classes of 2.6.5 that were seen drawing, each asked once which mixins of other mods it carries. */
    private static void noteRendererClass(Class<?> type) {
        for (int index = 0; index < rendererClasses; index++) {
            if (RENDERER_CLASSES[index] == type) {
                return;
            }
        }
        if (rendererClasses == MAX_RENDERER_CLASSES) {
            return;
        }
        RENDERER_CLASSES[rendererClasses++] = type;
        String line = Text.clean(type.getSimpleName()) + ": " + foreignMixins(type);
        FOREIGN_MIXINS.add(line);
        LOGGER.info("ysm_ar: mixins of other mods on the Yes Steve Model renderer class {}", line);
    }

    private static void off(String reason) {
        activation = OFF;
        offReason = reason;
        handles = null;
        WINDOWS.clear();
        LOGGER.info("ysm_ar: the take-over of Yes Steve Model models is off for this session: {}", reason);
    }

    private static void switchOff(Throwable problem) {
        Throwable cause = problem instanceof InvocationTargetException && problem.getCause() != null ? problem.getCause() : problem;
        activation = OFF;
        offReason = "an error: " + Text.clean(NativeLoader.describe(cause));
        LOGGER.error("ysm_ar: the take-over of Yes Steve Model models is off for this session after an error; Yes Steve Model draws as before", cause);
    }

    private static void lost() {
        TakeoverStats.lost();
        activation = OFF;
        offReason = "a body that was hidden from Yes Steve Model was never drawn";
        if (!lostLogged) {
            lostLogged = true;
            LOGGER.error("ysm_ar: a body that was hidden from Yes Steve Model was not drawn: the frame ended before the scale call of the"
                    + " pre-render step, the Post event or another Pre event came. The take-over is off for this session; Yes Steve Model draws as before");
        }
    }

    /**
     * DECIDE. Runs right after 2.6.5's own animation update for the entity, at the visibility test. Everything that
     * can speak against the take-over is asked here, before anything is hidden.
     */
    private static Decision decide(Render window) throws Exception {
        YsmHandles members = window.members;
        LivingEntity entity = (LivingEntity) window.entity;
        Minecraft minecraft = Minecraft.getInstance();
        // What the vanilla method, and the line after its call in 2.6.5, answer for an invisible entity: no body.
        if (entity.isInvisible() || minecraft.player == null || entity.isInvisibleTo(minecraft.player)) {
            TakeoverStats.invisible();
            return Decision.LEAVE;
        }
        if (!YsmArSubmitter.renderingLevel()) {
            return fallback(null, Fallback.NOT_LEVEL);
        }
        Object animatable = members.animatable(entity);
        if (animatable == null) {
            return fallback(null, Fallback.NO_ANIMATABLE);
        }
        if (!Boolean.TRUE.equals(members.modelReady.invoke(animatable))) {
            return fallback(null, Fallback.MODEL_NOT_READY);
        }
        Object animated = members.animatedModel.invoke(animatable);
        Object model = animated == null ? null : members.model.invoke(animated);
        float[] live = animated == null ? null : (float[]) members.liveFloats.invoke(animated);
        if (model == null || live == null) {
            return fallback(null, Fallback.MODEL_NOT_READY);
        }
        Object own = members.textureLocation.invoke(animatable);
        Bindings.Binding binding = bindings.find(members, model, animated, (String) members.modelId.invoke(animatable),
                (String) members.textureName.invoke(animatable), own);
        if (binding.state != Bindings.State.READY) {
            return fallback(binding, binding.state == Bindings.State.LOADING ? Fallback.LOADING : Fallback.NO_BINDING);
        }
        if (!AttributeMap.sized(live, binding.bones)) {
            return fallback(binding, Fallback.ARRAY_SIZE);
        }
        YsmArConfig config = YsmArConfig.current();
        boolean providePivots = config.takeoverProvidePivotAbs && members.pivotFloats != null;
        int flagged = AttributeMap.flagged(live, binding.bones);
        if (flagged > 0) {
            binding.flaggedBones = flagged;
            if (!providePivots) {
                return fallback(binding, Fallback.SLOT_11);
            }
            if (!PivotAbs.sized((float[]) members.pivotFloats.invoke(animated), binding.bones)) {
                return fallback(binding, Fallback.PIVOT_ARRAY);
            }
        }
        float width = (Float) members.widthScale.invoke(animatable);
        float height = (Float) members.heightScale.invoke(animatable);
        if (!Float.isFinite(width) || !Float.isFinite(height) || width == 0.0f || height == 0.0f) {
            return fallback(binding, Fallback.SCALE);
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        ResourceLocation texture = ((EntityRenderer) window.renderer).getTextureLocation(entity);
        // The mesh was split by the alpha of the model's own texture; another texture is not ours to draw. For a
        // player the texture a listener of 2.6.5's own event set is what 2.6.5 would draw with.
        if (texture == null || !texture.equals(own) || TextureEventTap.overrides(window.eventTexture, own)) {
            return fallback(binding, Fallback.TEXTURE);
        }
        int sideBySide = config.takeoverSideBySide;
        LateRead.Armed armed = new LateRead.Armed();
        armed.entry = binding.entry;
        armed.permutation = binding.permutation;
        armed.attributes = binding.attributes;
        armed.liveCopy = binding.liveCopy;
        armed.glow = binding.glow();
        armed.flagsAllowed = providePivots;
        armed.width = width;
        armed.height = height;
        armed.light = window.light;
        armed.overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0f);
        armed.shift = sideBySide == 0 ? null : shiftOnScreen(sideBySide);
        // 2.6.5 applies the model scale after the visibility test; its stack is copied, never changed.
        if (!LateRead.decide(LateRead.GAME, armed, live, ((PoseStack) window.stack).last(), window.buffers, texture)) {
            YsmArStats.Rejection rejection = YsmArStats.lastRejection();
            if (rejection == YsmArStats.Rejection.BAD_ATTRIBUTES && binding.attributeRefusal == null) {
                // The array the check refused is still in the scratch of the binding; "the caller" is 2.6.5's bone order.
                String detail = Attributes.refusal(binding.attributes, binding.bones, config.takeoverPruneNonFinite, binding.permutation);
                if (detail != null) {
                    bindings.attributesRefused(binding, detail.replace("of the caller", "of Yes Steve Model")
                            + (detail.contains("not finite") ? "; takeover.non_finite=refuse leaves such a model to Yes Steve Model" : ""));
                }
            }
            return fallback(binding, rejection == YsmArStats.Rejection.PREWARMING ? Fallback.PREWARMING : Fallback.SUBMIT);
        }
        window.animatable = animatable;
        window.animated = animated;
        window.binding = binding;
        window.draw = armed;
        window.sideBySide = sideBySide != 0;
        if (lateRead(config)) {
            window.width = width;
            window.height = height;
            return window.sideBySide ? Decision.ARM_SHOWN : Decision.ARM;
        }
        // No late hook: the body is drawn here, with the pose of now and the model scale.
        window.shown = window.sideBySide;
        emit(window, IMMEDIATE, null);
        return window.drawn && !window.shown ? Decision.DRAWN : Decision.LEAVE;
    }

    /**
     * EMIT. point: where (see Windows), or IMMEDIATE inside DECIDE. Reads the bone values again and draws; then does
     * what 2.6.5's own draw would have done besides drawing.
     */
    private static void emit(Render window, int point, Object lateStack) throws Exception {
        YsmHandles members = window.members;
        Bindings.Binding binding = window.binding;
        LateRead.Armed armed = window.draw;
        float[] liveNow = null;
        PoseStack.Pose late = null;
        if (point != IMMEDIATE) {
            try {
                liveNow = (float[]) members.liveFloats.invoke(window.animated);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                liveNow = null;
            }
            late = point == Windows.AT_SCALE ? ((PoseStack) lateStack).last() : null;
        }
        boolean drawn = LateRead.emit(LateRead.GAME, armed, liveNow, late);
        if (point != IMMEDIATE) {
            TakeoverStats.emitted(point, armed.bonesChanged, armed.poseChanged, armed.extractedAgain, armed.copyDrawn);
            binding.lateBones += armed.bonesChanged ? 1 : 0;
            binding.latePose += armed.poseChanged ? 1 : 0;
            binding.secondExtracts += armed.extractedAgain ? 1 : 0;
            if (point != Windows.AT_SCALE && !missedLogged) {
                missedLogged = true;
                LOGGER.warn("ysm_ar: a taken-over body was drawn {} because the scale call of Yes Steve Model's pre-render step did not come"
                                + " (model {}): it has the pose of the visibility test. Further such draws are only counted (/ysm_ar status: missed)",
                        point == Windows.AT_POST ? "at the Post event" : "when its render was closed", Text.clean(binding.modelId));
            }
        }
        if (!drawn) {
            if (point == IMMEDIATE) {
                fallback(binding, Fallback.SUBMIT);
                return;
            }
            // The body is hidden from 2.6.5 already: it is missing in this frame. The model is not used again.
            TakeoverStats.emitFailed();
            binding.entry.disable("a body hidden from Yes Steve Model could not be drawn");
            if (!emitFailureLogged) {
                emitFailureLogged = true;
                LOGGER.error("ysm_ar: the body of {} was hidden from Yes Steve Model and could then not be drawn; it is missing for one frame"
                        + " and the model stays with Yes Steve Model from now on", Text.clean(binding.modelId));
            }
            return;
        }
        window.drawn = true;
        if (window.sideBySide) {
            // Both are on screen now: ours moved aside, and the body 2.6.5 goes on to draw.
            fallback(binding, Fallback.SIDE_BY_SIDE);
            return;
        }
        if (armed.flagged > 0 && members.pivotFloats != null) {
            // 2.6.5 fills this array in the draw it skips; the state still holds the Extract of this draw.
            float[] pivotValues = (float[]) members.pivotFloats.invoke(window.animated);
            if (PivotAbs.sized(pivotValues, binding.bones)) {
                binding.pivotBones = PivotAbs.write(binding.entry.data(), binding.permutation, pivotValues);
                binding.pivotRenders++;
            }
        }
        // Without this 2.6.5 would count the entity as not drawn and slow its animation down.
        members.markRendered.invoke(window.animatable);
        binding.takenOver++;
    }

    /**
     * A matrix that moves what is drawn a whole number of pixels to the right on screen, with nothing else changed:
     * the same perspective, depth, face culling and lighting as at its own place. In clip space that is x + c * w,
     * so a pose becomes (projection * view)^-1 * shift * (projection * view) * pose.
     */
    private static Matrix4f shiftOnScreen(int pixels) {
        Matrix4f clip = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
        float c = 2.0f * pixels / Minecraft.getInstance().getMainRenderTarget().width;
        return clip.invert(new Matrix4f()).mul(new Matrix4f().m30(c)).mul(clip);
    }

    private static Decision fallback(Bindings.Binding binding, Fallback reason) {
        TakeoverStats.fallback(reason);
        if (binding != null) {
            binding.fallbacks++;
            binding.lastFallback = reason;
        }
        return Decision.LEAVE;
    }
}
