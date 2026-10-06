package ysmar.takeover;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The public members of Yes Steve Model 2.6.5 this mod reads, looked up by name and descriptor at run time. The
 * names are those of that one build; any other build, or one required member that is not there as written, and
 * nothing is resolved. Optional members only add something (a guard, a mode) and may be missing one by one. No class
 * of that mod (or of Touhou Little Maid) is named in code.
 */
final class YsmHandles implements Bindings.Source {
    static final String MOD_ID = YsmBuild.MOD_ID;
    static final String VERSION = YsmBuild.VERSION;
    static final int REQUIRED = 12;

    private static final String PACKAGE = "com.elfmcys.yesstevemodel.";
    private static final String RENDERER_BASE = "OO0OOoo0ooooOoO0O0o00Ooo";
    private static final String RENDERER_INTERFACE = "ooo0000oO0O0ooOO0ooOO0o0";
    private static final String ANIMATABLE_BASE = "OoO0oo0o0o0oOoo0oOOO0Ooo";
    private static final String ANIMATABLE_HOLDER = "o0o0OO0O00000oOo00o0oOOO";
    private static final String ANIMATABLE_LIVING = "O0OOoooOOoOo0O00O0oOoo0O";
    private static final String ANIMATED_MODEL = "o0ooO0ooO00oo0o00Oo00000";
    private static final String MODEL = "O0OOo0OoOoo0o0o0oO000o00";
    private static final String BONE = "O0o0OoO0O0ooo0o0ooo0OOOo";
    private static final String RUNTIME_BONE = "ooOO0OoOoO0o0o00oO0oo00o";
    private static final String PLAYER_TEXTURE_EVENT = "O00O000ooOOooOoOoOo0oo0o";
    private static final String M0 = "oOo0OO0O0o000OO0O000oo0o";

    /** Pivot x, y, z and rest rotation x, y, z of a bone of the model, as its constructor takes them. */
    private static final String[] BONE_PIVOT = {"O0o0OoOOooOo0O0OOoo0Oo00", "OoooO0OO0000O00oo0Oo00OO", "oOOO00ooO0oOOoOOo0OoOOOo"};
    private static final String[] BONE_ROTATION = {"ooOooOO0oO00o00o0o0oOOoO", "OOo0O00Ooo00O0Ooo0OoOo0o", "OO0Oo0O00OOOo0oOo0oooooO"};
    /**
     * Getters of the animated model whose lists 2.6.5 hands to its one method that walks a bone chain from a root to
     * a locator bone: nine give one chain, three a list of chains.
     */
    private static final String[] CHAINS = {"Oo0O0OoOo0O0oOoo0000O0oO", "OoooO0OO0000O00oo0Oo00OO", "OOo0O00Ooo00O0Ooo0OoOo0o",
            "O0O0Oo0Oooo0OOoOOO0ooo0O", "OoOO0o0O00o00OoOO0OO0OOo", "O0OO0O0o00o0o00oOoO0o0oO", "oo000ooO0O00oo0OO0ooO000",
            "oO0O000o0oooOOO0O0oooOO0", "OO0Oo0O00OOOo0oOo0oooooO"};
    private static final String[] CHAIN_GROUPS = {"O0o0OoOOooOo0O0OOoo0Oo00", "oOOO00ooO0oOOoOOo0OoOOOo", "ooOooOO0oO00o00o0o0oOOoO"};

    /** The renderer class both the player and the maid renderer extend, and the interface its draw steps come from. */
    final Class<?> rendererBase;
    final Class<?> rendererInterface;
    final Class<?> livingAnimatable;
    final Class<?> bone;
    final AttachmentType<?> playerAnimatable;
    /** Null while Touhou Little Maid is not installed. */
    final AttachmentType<?> maidAnimatable;

    final Method animatedModel;
    /** The one member that is called for an effect: marks the entity as drawn this frame. */
    final Method markRendered;
    final Method widthScale;
    final Method heightScale;
    final Method modelId;
    final Method modelReady;
    final Method textureName;
    final Method textureLocation;
    final Method liveFloats;
    final Method model;
    final Method bones;
    final Method boneName;

    /** Optional: the array of 4 floats per bone behind the Molang function bone_pivot_abs. */
    final Method pivotFloats;
    /** Optional, three getters each or null. */
    final Method[] bonePivot;
    final Method[] boneRotation;
    /** Optional: the chain getters that are there, and the name of a bone in such a chain. */
    final List<Method> chains = new ArrayList<>();
    final List<Method> chainGroups = new ArrayList<>();
    final Class<?> runtimeBone;
    final Method runtimeBoneName;
    /** Optional: the event the player renderer posts before a render, its player and the texture a listener set. */
    final Class<?> playerTextureEvent;
    final Method eventPlayer;
    final Method eventTexture;

    private YsmHandles(ClassLoader loader) throws Members.Missing {
        rendererBase = type(RENDERER_BASE, loader);
        if (!LivingEntityRenderer.class.isAssignableFrom(rendererBase)) {
            throw new Members.Missing("the renderer base class does not extend the vanilla LivingEntityRenderer");
        }
        Class<?> animatableBase = type(ANIMATABLE_BASE, loader);
        Class<?> holder = type(ANIMATABLE_HOLDER, loader);
        livingAnimatable = type(ANIMATABLE_LIVING, loader);
        if (!animatableBase.isAssignableFrom(holder) || !holder.isAssignableFrom(livingAnimatable)) {
            throw new Members.Missing("the animatable classes are not related as expected");
        }
        Class<?> animated = type(ANIMATED_MODEL, loader);
        Class<?> modelType = type(MODEL, loader);
        bone = type(BONE, loader);

        animatedModel = Members.method(animatableBase, "O00OOOo00Oo0OO0000oOo0oo", "()" + descriptor(ANIMATED_MODEL), false);
        markRendered = Members.method(animatableBase, "o00o00Oo0OO00oooO0OOo00o", "()V", false);
        widthScale = Members.method(animatableBase, "c_", "()F", false);
        heightScale = Members.method(animatableBase, "e_", "()F", false);
        modelId = Members.method(holder, "O000OOo000oOOo0OO0oo0o0O", "()Ljava/lang/String;", false);
        modelReady = Members.method(holder, "OOO0oooOOo00OOooo0OooOOo", "()Z", false);
        textureName = Members.method(livingAnimatable, "oO0o0O0OoOOooooOo00O0o0o", "()Ljava/lang/String;", false);
        textureLocation = Members.method(livingAnimatable, "b_", "()Lnet/minecraft/resources/ResourceLocation;", false);
        liveFloats = Members.method(animated, M0, "()[F", false);
        model = Members.method(animated, "OOo0o0000Ooo0o00OO0oOOoO", "()" + descriptor(MODEL), false);
        bones = Members.method(modelType, M0, "()Ljava/util/List;", false);
        boneName = Members.method(bone, M0, "()Ljava/lang/String;", false);

        pivotFloats = optional(animated, "oOoo00O0o0oO0o0oO00OO0O0", "()[F");
        bonePivot = optional(bone, BONE_PIVOT, "()F");
        boneRotation = optional(bone, BONE_ROTATION, "()F");
        Class<?> chainBone = optionalType(RUNTIME_BONE, loader);
        runtimeBoneName = chainBone == null ? null : optional(chainBone, "OOO0oooOOo00OOooo0OooOOo", "()Ljava/lang/String;");
        runtimeBone = runtimeBoneName == null ? null : chainBone;
        if (runtimeBone != null) {
            for (String name : CHAINS) {
                add(chains, optional(animated, name, "()Ljava/util/List;"));
            }
            for (String name : CHAIN_GROUPS) {
                add(chainGroups, optional(animated, name, "()Ljava/util/List;"));
            }
        }
        Class<?> event = optionalType(PLAYER_TEXTURE_EVENT, loader);
        Method player = event == null ? null : optional(event, M0, "()Lnet/minecraft/world/entity/player/Player;");
        Method texture = event == null ? null : optional(event, "OOo0o0000Ooo0o00OO0oOOoO", "()Lnet/minecraft/resources/ResourceLocation;");
        boolean usable = player != null && texture != null && net.neoforged.bus.api.Event.class.isAssignableFrom(event)
                && net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(event);
        playerTextureEvent = usable ? event : null;
        eventPlayer = usable ? player : null;
        eventTexture = usable ? texture : null;
        rendererInterface = optionalType(RENDERER_INTERFACE, loader);

        playerAnimatable = NeoForgeRegistries.ATTACHMENT_TYPES.get(ResourceLocation.fromNamespaceAndPath(MOD_ID, "animatable"));
        if (playerAnimatable == null) {
            throw new Members.Missing("attachment type yes_steve_model:animatable is not registered");
        }
        maidAnimatable = NeoForgeRegistries.ATTACHMENT_TYPES.get(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ysm_maid"));
    }

    /** Throws when the installed mod is not exactly the build these names belong to, or a member is missing. */
    static YsmHandles resolve() throws Members.Missing {
        ModList mods = ModList.get();
        String version = mods == null ? null : mods.getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString()).orElse(null);
        if (version == null) {
            throw new Members.Missing("Yes Steve Model is not installed");
        }
        if (!VERSION.equals(version)) {
            throw new Members.Missing("Yes Steve Model " + version + " is installed, this mod knows the members of " + VERSION + " only");
        }
        return new YsmHandles(YsmHandles.class.getClassLoader());
    }

    /** Which optional members are there, for the log and the status. listening: the listener for the event is registered. */
    String describeOptional(boolean listening) {
        return "bone_pivot_abs array " + yes(pivotFloats != null)
                + ", player texture event " + (playerTextureEvent == null ? "no" : listening ? "yes (listener registered)" : "yes, BUT THE LISTENER COULD NOT BE REGISTERED")
                + ", bone pivots " + yes(bonePivot != null)
                + ", bone rest rotations " + yes(boneRotation != null)
                + ", locator chain lists " + (chains.size() + chainGroups.size()) + " of " + (CHAINS.length + CHAIN_GROUPS.length);
    }

    /** The animatable 2.6.5 keeps for the entity, or null. Never creates one. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object animatable(LivingEntity entity) {
        AttachmentType<?> type = entity instanceof Player ? playerAnimatable : maidAnimatable;
        if (type == null) {
            return null;
        }
        Optional<?> data = entity.getExistingData((AttachmentType) type);
        Object animatable = data.orElse(null);
        return livingAnimatable.isInstance(animatable) ? animatable : null;
    }

    @Override
    public Bindings.Bones bones(Object model) throws ReflectiveOperationException {
        Object list = bones.invoke(model);
        if (!(list instanceof List<?> all)) {
            return new Bindings.Bones(null, "2.6.5 gives no bone list for the model");
        }
        String[] names = new String[all.size()];
        for (int index = 0; index < names.length; index++) {
            Object element = all.get(index);
            if (!bone.isInstance(element)) {
                return new Bindings.Bones(null, "the bone list of 2.6.5 holds objects of an unexpected class");
            }
            names[index] = (String) boneName.invoke(element);
        }
        return new Bindings.Bones(names, null);
    }

    @Override
    public ContentGuards.Theirs content(Object model, Object animated, String[] names) {
        ContentGuards.Theirs theirs = new ContentGuards.Theirs();
        try {
            Object list = bones.invoke(model);
            if (list instanceof List<?> all && all.size() == names.length) {
                theirs.pivots = floats(all, bonePivot);
                theirs.rotations = floats(all, boneRotation);
            }
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            theirs.pivots = null;
            theirs.rotations = null;
        }
        if (animated != null && runtimeBone != null && !chains.isEmpty()) {
            try {
                Map<String, Integer> positions = Bindings.index(names);
                List<int[]> found = new ArrayList<>();
                for (Method getter : chains) {
                    chain(found, getter.invoke(animated), positions);
                }
                for (Method getter : chainGroups) {
                    if (getter.invoke(animated) instanceof List<?> group) {
                        for (Object element : group) {
                            chain(found, element, positions);
                        }
                    }
                }
                theirs.chains = found.toArray(new int[0][]);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                theirs.chains = null;
            }
        }
        return theirs;
    }

    /** The size of level 0 of the texture as it is on the graphics card; nothing is bound and nothing is read back. */
    @Override
    public int[] textureSize(Object texture) {
        if (!(texture instanceof ResourceLocation location) || !GL.getCapabilities().OpenGL45) {
            return null;
        }
        AbstractTexture uploaded = Minecraft.getInstance().getTextureManager().getTexture(location, null);
        if (uploaded == null) {
            return null;
        }
        return ContentGuards.textureSize(GL_TEXTURES, uploaded.getId());
    }

    private static final ContentGuards.TextureQuery GL_TEXTURES = new ContentGuards.TextureQuery() {
        @Override
        public boolean exists(int id) {
            return GL11.glIsTexture(id);
        }

        @Override
        public int width(int id) {
            return GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_WIDTH);
        }

        @Override
        public int height(int id) {
            return GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_HEIGHT);
        }
    };

    private float[] floats(List<?> all, Method[] getters) throws ReflectiveOperationException {
        if (getters == null) {
            return null;
        }
        float[] values = new float[all.size() * 3];
        for (int index = 0; index < all.size(); index++) {
            Object element = all.get(index);
            if (!bone.isInstance(element)) {
                return null;
            }
            for (int axis = 0; axis < 3; axis++) {
                values[index * 3 + axis] = (Float) getters[axis].invoke(element);
            }
        }
        return values;
    }

    private void chain(List<int[]> found, Object value, Map<String, Integer> positions) throws ReflectiveOperationException {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        int[] indices = new int[list.size()];
        for (int index = 0; index < indices.length; index++) {
            Object element = list.get(index);
            Integer position = runtimeBone.isInstance(element) ? positions.get((String) runtimeBoneName.invoke(element)) : null;
            indices[index] = position == null ? -1 : position;
        }
        found.add(indices);
    }

    private static String yes(boolean there) {
        return there ? "yes" : "no";
    }

    private static void add(List<Method> list, Method method) {
        if (method != null) {
            list.add(method);
        }
    }

    private static Method optional(Class<?> owner, String name, String descriptor) {
        try {
            return Members.method(owner, name, descriptor, false);
        } catch (Members.Missing | LinkageError absent) {
            return null;
        }
    }

    private static Method[] optional(Class<?> owner, String[] names, String descriptor) {
        Method[] methods = new Method[names.length];
        for (int index = 0; index < names.length; index++) {
            methods[index] = optional(owner, names[index], descriptor);
            if (methods[index] == null) {
                return null;
            }
        }
        return methods;
    }

    private static Class<?> optionalType(String name, ClassLoader loader) {
        try {
            return type(name, loader);
        } catch (Members.Missing absent) {
            return null;
        }
    }

    private static Class<?> type(String name, ClassLoader loader) throws Members.Missing {
        return Members.type(PACKAGE + name, loader);
    }

    private static String descriptor(String name) {
        return "L" + PACKAGE.replace('.', '/') + name + ";";
    }
}
