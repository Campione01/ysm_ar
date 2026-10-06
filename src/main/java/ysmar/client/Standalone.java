package ysmar.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ysmar.ModelEntry;
import ysmar.YsmArConfig;
import ysmar.YsmArModels;
import ysmar.YsmArSubmitter;
import ysmar.core.Attributes;
import ysmar.core.ModelData;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Test front-end: draws the configured model in place of every entity of the configured type. The vanilla model is
 * only suppressed for a draw that Accelerated Rendering took completely.
 */
final class Standalone {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");

    /** The settings turned into objects once. */
    private static final class Target {
        YsmArConfig config;
        Path model;
        EntityType<?> type;
        String problem;
    }

    /** What the front-end keeps per loaded model. */
    private static final class Front {
        ModelEntry entry;
        ResourceLocation texture;
        float[] rest;
        float[] scratch;
        int[] waveBones;
    }

    private static Target target;
    private static final List<Front> FRONTS = new ArrayList<>();
    private static int textureCounter;
    private static String failure;

    private Standalone() {
    }

    static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (failure != null) {
            return;
        }
        YsmArConfig config = YsmArConfig.current();
        if (!config.enabled || config.standaloneModel.isEmpty()) {
            return;
        }
        try {
            render(event, config);
        } catch (Throwable problem) {
            failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
            LOGGER.error("ysm_ar: the stand-alone front-end is switched off after an error", problem);
        }
    }

    private static void render(RenderLivingEvent.Pre<?, ?> event, YsmArConfig config) {
        Target current = target(config);
        LivingEntity entity = event.getEntity();
        if (current.type == null || entity.getType() != current.type) {
            return;
        }
        // Nothing is loaded while Accelerated Rendering could not take the model anyway.
        if (!YsmArSubmitter.ready()) {
            return;
        }
        ModelEntry entry = YsmArModels.request(current.model, config.standaloneTexture.isEmpty() ? null : config.standaloneTexture, true);
        ModelData data = entry.data();
        if (data == null) {
            return;
        }
        Front front = front(entry, data);
        float partialTick = event.getPartialTick();
        float[] attributes = front.rest;
        if (config.standaloneWave) {
            // A function of the entity's age alone: every render pass of a frame sees the same pose.
            Attributes.wave(front.rest, front.waveBones, entity.tickCount + (double) partialTick, front.scratch);
            attributes = front.scratch;
        }
        float width = Float.isNaN(config.standaloneScale) ? data.widthScale : config.standaloneScale;
        float height = Float.isNaN(config.standaloneScale) ? data.heightScale : config.standaloneScale;
        int overlay = OverlayTexture.pack(OverlayTexture.u(0.0f), OverlayTexture.v(entity.hurtTime > 0 || entity.deathTime > 0));

        PoseStack stack = event.getPoseStack();
        boolean submitted;
        stack.pushPose();
        try {
            float scale = entity.getScale();
            stack.scale(scale, scale, scale);
            stack.mulPose(Axis.YP.rotationDegrees(180.0f - Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)));
            stack.translate(0.0f, 0.01f, 0.0f);
            stack.scale(width, height, width);
            submitted = YsmArSubmitter.submit(entry, attributes, stack.last(), event.getMultiBufferSource(), front.texture,
                    event.getPackedLight(), overlay);
        } finally {
            stack.popPose();
        }
        if (submitted) {
            event.setCanceled(true);
        }
    }

    private static Target target(YsmArConfig config) {
        Target current = target;
        if (current != null && current.config == config) {
            return current;
        }
        current = new Target();
        current.config = config;
        try {
            current.model = Path.of(config.standaloneModel);
            ResourceLocation id = ResourceLocation.tryParse(config.standaloneEntity);
            current.type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (current.type == null) {
                current.problem = "standalone.entity names no entity type";
            }
        } catch (RuntimeException problem) {
            current.type = null;
            current.problem = "standalone.model is not a usable path";
        }
        if (current.problem != null) {
            LOGGER.warn("ysm_ar: the stand-alone front-end is off: {}", current.problem);
        }
        target = current;
        return current;
    }

    private static Front front(ModelEntry entry, ModelData data) {
        if (entry.frontEndData() instanceof Front existing) {
            return existing;
        }
        releaseUnused();
        ByteBuffer pixels = data.pixels;
        if (pixels == null) {
            throw new IllegalStateException("the model was loaded without its texture");
        }
        int width = data.textureWidth;
        int height = data.textureHeight;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                int row = y * width * 4;
                for (int x = 0; x < width; x++) {
                    image.setPixelRGBA(x, y, pixels.getInt(row + x * 4));
                }
            }
        } catch (Throwable problem) {
            image.close();
            throw problem;
        }
        Front front = new Front();
        front.entry = entry;
        front.texture = ResourceLocation.fromNamespaceAndPath("ysm_ar", "standalone/" + ++textureCounter);
        Minecraft.getInstance().getTextureManager().register(front.texture, new DynamicTexture(image));
        data.pixels = null;
        front.rest = Attributes.rest(data.restRotations);
        front.scratch = new float[front.rest.length];
        front.waveBones = Attributes.waveBones(data);
        FRONTS.add(front);
        entry.frontEndData(front);
        LOGGER.info("ysm_ar: front-end texture {} ({}x{}) for {}, {} of {} bones swing in the wave animation", front.texture,
                width, height, ysmar.Text.clean(entry.fileName()), front.waveBones.length, data.boneCount);
        return front;
    }

    /** Frees the textures of models that are no longer in the cache. */
    private static void releaseUnused() {
        for (Iterator<Front> iterator = FRONTS.iterator(); iterator.hasNext(); ) {
            Front front = iterator.next();
            if (front.entry.state() != ModelEntry.State.READY) {
                Minecraft.getInstance().getTextureManager().release(front.texture);
                iterator.remove();
            }
        }
    }

    static void reset() {
        for (Front front : FRONTS) {
            Minecraft.getInstance().getTextureManager().release(front.texture);
        }
        FRONTS.clear();
        target = null;
        failure = null;
    }

    static String describe() {
        YsmArConfig config = YsmArConfig.current();
        if (!config.enabled) {
            return "off (enabled=false)";
        }
        if (config.standaloneModel.isEmpty()) {
            return "off (standalone.model is empty)";
        }
        if (failure != null) {
            return "switched off after an error: " + failure;
        }
        Target current = target;
        if (current != null && current.config == config && current.problem != null) {
            return "off: " + current.problem;
        }
        return "replaces " + config.standaloneEntity + ", " + FRONTS.size() + " texture(s) registered"
                + (current == null || current.config != config ? " (no such entity was drawn yet)" : "");
    }
}
