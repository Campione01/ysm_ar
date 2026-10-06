package ysmar.core;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.legacy.LegacyImport;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * File to model: legacy import, PLAYER render target, geo model "main", texture, bake, cube walk. Runs on the
 * loader thread. Decoded content stays in memory; messages carry counts and reasons only.
 */
public final class ModelLoader {
    /** The official loader bakes every model with the UV rules of this version, whatever version wrote the file. */
    public static final int ORIGIN_VERSION = 29;
    /**
     * The name the one bone with an empty name gets before the bake, which refuses empty names. Yes Steve Model
     * 2.6.5 keeps the empty name; nothing can name such a bone as a parent, so only its own name changes.
     */
    public static final String EMPTY_BONE_NAME = "ysm_ar:bone_without_name";
    private static final float DEFAULT_SCALE = 0.7f;
    /** Native diagnostics that end in a name taken from the file; only this fixed part of them is reported. */
    private static final String[] DIAGNOSTICS_WITH_A_NAME = {"GUI image has no target role"};

    public static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;

        Refusal(String reason) {
            super(reason, null, false, false);
        }
    }

    private ModelLoader() {
    }

    /** What the import step hands over to the bake once the import itself is closed. */
    private static final class Imported {
        Proto.GeoModel geo;
        NativeBuffer geoBytes;
        String emptyBoneName;
        NativeBuffer pixels;
        int width;
        int height;
        boolean hasPbr;
        boolean forceCulling;
        int originVersion;
        float heightScale;
        float widthScale;
        List<String> textureKeys;
        String textureKey;
        double importMillis;
        double decodeMillis;

        void close() {
            if (geoBytes != null) {
                geoBytes.close();
            }
            if (pixels != null) {
                pixels.close();
            }
        }
    }

    public static ModelData load(Path file, String textureKeyOrNull, boolean keepPixels, Consumer<List<String>> textureKeys)
            throws Refusal {
        return load(file, textureKeyOrNull, keepPixels, textureKeys, false);
    }

    /**
     * fileRules: bake with the UV rules of the version the file was exported with, which is what Yes Steve Model
     * 2.6.5 shows; otherwise with those of version 29, as the official loader does. They differ in how UVs are
     * rounded to texels, by up to one texel row or column per quad.
     */
    public static ModelData load(Path file, String textureKeyOrNull, boolean keepPixels, Consumer<List<String>> textureKeys,
                                 boolean fileRules) throws Refusal {
        long start = System.nanoTime();
        Imported imported = importModel(file, textureKeyOrNull, textureKeys);
        try {
            return bake(imported, keepPixels, start, fileRules && imported.originVersion >= 0 ? imported.originVersion : ORIGIN_VERSION);
        } finally {
            imported.close();
        }
    }

    private static Imported importModel(Path file, String textureKeyOrNull, Consumer<List<String>> textureKeys) throws Refusal {
        long start = System.nanoTime();
        LegacyImport source;
        try {
            source = LegacyImport.open(file);
        } catch (IllegalStateException problem) {
            throw new Refusal("import: result protocol error: " + problem.getMessage());
        }
        Imported result = new Imported();
        boolean complete = false;
        try {
            if (!source.succeeded()) {
                throw new Refusal(importReason(source.statusName(), source.diagnostic()));
            }
            result.importMillis = (System.nanoTime() - start) / 1e6;

            Proto.Manifest manifest;
            try (ArrayBuffer array = source.payload(0).acquireArray()) {
                manifest = Proto.manifest(ByteBuffer.wrap(array.array()), array.arrayOffset(), array.size());
            }
            Proto.RenderTarget player = null;
            for (Proto.RenderTarget target : manifest.targets) {
                if (target.kind == Proto.KIND_PLAYER) {
                    player = target;
                    break;
                }
            }
            if (player == null) {
                throw new Refusal("the model has no PLAYER render target");
            }
            result.forceCulling = player.forceCulling;
            result.originVersion = manifest.originVersion;
            result.heightScale = validScale(player.heightScale);
            result.widthScale = validScale(player.widthScale);

            List<String> keys = new ArrayList<>();
            for (Proto.TextureSet texture : player.textures) {
                keys.add(texture.name);
            }
            result.textureKeys = Collections.unmodifiableList(keys);
            textureKeys.accept(result.textureKeys);
            Proto.TextureSet chosen;
            if (textureKeyOrNull != null) {
                chosen = player.texture(textureKeyOrNull);
                if (chosen == null) {
                    throw new Refusal("the requested texture key is not among the " + keys.size() + " keys of the PLAYER target");
                }
            } else {
                // ModelManifestLookup.chooseTexture: the manifest default if it is a key, else the first key.
                chosen = manifest.defaultTexture == null ? null : player.texture(manifest.defaultTexture);
                if (chosen == null && !player.textures.isEmpty()) {
                    chosen = player.textures.get(0);
                }
            }
            if (chosen == null || chosen.uv == null) {
                throw new Refusal("the PLAYER render target has no texture");
            }
            result.textureKey = chosen.name;
            result.hasPbr = chosen.hasPbr();

            int modelIndex = findPayload(source, "MODEL_DATA", player.blobId);
            if (modelIndex < 0) {
                throw new Refusal("the model data payload of the PLAYER target is missing");
            }
            NativeBuffer modelPayload = source.payload(modelIndex);
            ByteBuffer modelBytes = modelPayload.nio();
            Proto.GeoEntry main = Proto.geoEntry(modelBytes, 0, modelPayload.size(), "main");
            if (main == null) {
                throw new Refusal("the PLAYER model data has no geo model named main");
            }
            adoptGeo(result, modelBytes, main.offset, main.length);

            int imageIndex = findPayload(source, "BLOB_IMAGE", chosen.uv.blobId);
            if (imageIndex < 0) {
                throw new Refusal("the texture blob is not among the import payloads");
            }
            long decodeStart = System.nanoTime();
            try (Image image = Image.probe(source.payload(imageIndex))) {
                result.pixels = image.decodeToBuffer();
                result.width = image.width();
                result.height = image.height();
            } catch (java.io.UnsupportedEncodingException problem) {
                throw new Refusal("texture: " + problem.getMessage());
            }
            result.decodeMillis = (System.nanoTime() - decodeStart) / 1e6;
            if (result.width <= 0 || result.height <= 0 || (long) result.width * result.height * 4 > result.pixels.size()) {
                throw new Refusal("texture: decoded size does not match " + result.width + "x" + result.height);
            }
            complete = true;
            return result;
        } catch (Wire.WireException problem) {
            throw new Refusal("unreadable manifest or model data: " + problem.getMessage());
        } finally {
            source.close();
            if (!complete) {
                result.close();
            }
        }
    }

    /**
     * The reason of a refused import: the status and the diagnostic of the native. A diagnostic that carries a name
     * from the file is cut to its fixed part, and one that is not short plain text is left out: names are content.
     */
    public static String importReason(String status, String diagnostic) {
        String text = diagnostic == null ? "" : diagnostic.trim();
        for (String fixed : DIAGNOSTICS_WITH_A_NAME) {
            if (text.startsWith(fixed)) {
                text = fixed + " (name withheld)";
                break;
            }
        }
        boolean plain = text.length() <= 200;
        for (int index = 0; plain && index < text.length(); index++) {
            plain = text.charAt(index) >= 32 && text.charAt(index) < 127;
        }
        return "import: " + status + (plain && !text.isEmpty() ? ": " + text : "");
    }

    /**
     * Bakes a geo model given as bytes (asset/model/data/geo_model.proto) with a white texture of 16x16 texels, the
     * way a model file is baked after its import. For tests and tools; the native library has to be loaded.
     */
    public static ModelData bakeGeoModel(byte[] geoModel) throws Refusal {
        long start = System.nanoTime();
        if (!NativeLoader.isLoaded()) {
            throw new Refusal("native library: not loaded");
        }
        Imported imported = new Imported();
        try {
            adoptGeo(imported, ByteBuffer.wrap(geoModel), 0, geoModel.length);
            imported.width = 16;
            imported.height = 16;
            imported.pixels = NativeBuffer.allocate(imported.width * imported.height * 4);
            ByteBuffer rgba = imported.pixels.nio();
            for (int index = 0; index < imported.width * imported.height * 4; index++) {
                rgba.put(index, (byte) 0xFF);
            }
            imported.originVersion = -1;
            imported.heightScale = DEFAULT_SCALE;
            imported.widthScale = DEFAULT_SCALE;
            imported.textureKeys = List.of("white");
            imported.textureKey = "white";
            return bake(imported, false, start, ORIGIN_VERSION);
        } catch (Wire.WireException problem) {
            throw new Refusal("unreadable model data: " + problem.getMessage());
        } finally {
            imported.close();
        }
    }

    /** The first rule of the native bone validation the geo model breaks, or null. */
    public static String precheck(byte[] geoModel) {
        return precheck(Proto.geoModel(ByteBuffer.wrap(geoModel), 0, geoModel.length));
    }

    /**
     * Reads the bone list and copies the geo model for the bake. One bone with an empty name gets EMPTY_BONE_NAME in
     * that copy; more than one, or a model that already uses that name, is refused.
     */
    private static void adoptGeo(Imported result, ByteBuffer bytes, int offset, int length) throws Refusal {
        Proto.GeoModel geo = Proto.geoModel(bytes, offset, length);
        List<Proto.Bone> bones = geo.bones;
        if (bones.isEmpty()) {
            throw new Refusal("the geo model has no bones");
        }
        int unnamed = -1;
        int unnamedCount = 0;
        boolean nameTaken = false;
        for (int index = 0; index < bones.size(); index++) {
            Proto.Bone bone = bones.get(index);
            if (bone.name.isEmpty() && !bone.nameUnreadable) {
                unnamed = index;
                unnamedCount++;
            }
            nameTaken |= bone.name.equals(EMPTY_BONE_NAME) || EMPTY_BONE_NAME.equals(bone.parent);
        }
        byte[] copy;
        if (unnamedCount == 0) {
            copy = new byte[length];
            bytes.get(offset, copy, 0, length);
        } else if (unnamedCount > 1) {
            throw new Refusal(unnamedCount + " of the " + bones.size() + " bones have an empty name; one can be given a name, more cannot be told apart");
        } else if (nameTaken) {
            throw new Refusal("a bone has an empty name and the name this mod would give it is already used in the model");
        } else {
            copy = Proto.renameBone(bytes, offset, length, unnamed, EMPTY_BONE_NAME);
            Proto.GeoModel renamed = Proto.geoModel(ByteBuffer.wrap(copy), 0, copy.length);
            if (renamed.bones.size() != bones.size() || renamed.cubes != geo.cubes || !renamed.bones.get(unnamed).name.equals(EMPTY_BONE_NAME)) {
                throw new Refusal("a bone has an empty name and the geo model could not be written with another one");
            }
            geo = renamed;
            result.emptyBoneName = EMPTY_BONE_NAME;
        }
        result.geo = geo;
        result.geoBytes = NativeBuffer.allocate(copy.length);
        result.geoBytes.nio().put(0, copy);
    }

    private static ModelData bake(Imported imported, boolean keepPixels, long start, int originVersion) throws Refusal {
        List<Proto.Bone> bones = imported.geo.bones;
        int boneCount = bones.size();
        String precheck = precheck(imported.geo);
        ModelData data = new ModelData();
        boolean complete = false;
        try {
            short[] sorted;
            long bakeStart = System.nanoTime();
            try {
                NativeBakedModel.BakeResult baked = NativeBakedModel.bake(imported.geoBytes, boneCount, imported.pixels,
                        imported.width, imported.height, originVersion, imported.forceCulling, false, imported.hasPbr);
                data.bakeMillis = (System.nanoTime() - bakeStart) / 1e6;
                long readStart = System.nanoTime();
                try (NativeBuffer bakedData = baked.bakedData()) {
                    NativeBakedModel.ReadResult read = NativeBakedModel.read(bakedData, boneCount);
                    data.baked = read.bakedModel();
                    sorted = read.sortedBoneIndices();
                }
                data.readMillis = (System.nanoTime() - readStart) / 1e6;
                if (!Arrays.equals(sorted, baked.sortedBoneIndices()) || data.baked.getInfo().boneCount() != (boneCount & 0xFFFF)) {
                    throw new Refusal("bake and read disagree about the bone order");
                }
            } catch (RuntimeException problem) {
                // The native gives no reason; the pre-check names the first rule of its bone validation that fails.
                throw new Refusal("bake: " + problem.getMessage() + (precheck == null ? "" : " (" + precheck + ")"));
            }

            int[] sortedOfOriginal = new int[boneCount];
            Map<String, Integer> originalByName = new HashMap<>();
            boolean identity = true;
            for (int slot = 0; slot < boneCount; slot++) {
                int original = Short.toUnsignedInt(sorted[slot]);
                if (original >= boneCount) {
                    throw new Refusal("bake: sorted bone index out of range");
                }
                sortedOfOriginal[original] = slot;
                originalByName.put(bones.get(original).name, original);
                identity &= original == slot;
            }
            String[] names = new String[boneCount];
            int[] parents = new int[boneCount];
            float[] rest = new float[boneCount * 3];
            float[] pivots = new float[boneCount * 3];
            for (int slot = 0; slot < boneCount; slot++) {
                Proto.Bone bone = bones.get(Short.toUnsignedInt(sorted[slot]));
                names[slot] = bone.name;
                Integer parent = bone.parent == null || bone.parent.isEmpty() ? null : originalByName.get(bone.parent);
                parents[slot] = parent == null ? -1 : sortedOfOriginal[parent];
                if (parents[slot] >= slot) {
                    throw new Refusal("bake: sorted bone order is not parent first");
                }
                System.arraycopy(bone.rotate, 0, rest, slot * 3, 3);
                System.arraycopy(bone.pivot, 0, pivots, slot * 3, 3);
            }
            data.boneCount = boneCount;
            data.boneNames = List.of(names);
            data.parents = parents;
            data.restRotations = rest;
            data.pivots = pivots;
            data.emptyBoneName = imported.emptyBoneName;
            data.sortedIsIdentity = identity;
            data.heightScale = imported.heightScale;
            data.widthScale = imported.widthScale;
            data.textureKeys = imported.textureKeys;
            data.textureKey = imported.textureKey;
            data.textureWidth = imported.width;
            data.textureHeight = imported.height;
            data.hasPbr = imported.hasPbr;
            data.forceCulling = imported.forceCulling;
            data.originVersion = originVersion;
            data.fileOriginVersion = imported.originVersion;
            data.importMillis = imported.importMillis;
            data.decodeMillis = imported.decodeMillis;

            long walkStart = System.nanoTime();
            ByteBuffer rgba = imported.pixels.nio().order(ByteOrder.LITTLE_ENDIAN);
            int texels = imported.width * imported.height;
            byte[] alpha = new byte[texels];
            for (int index = 0; index < texels; index++) {
                alpha[index] = rgba.get(index * 4 + 3);
            }
            if (keepPixels) {
                ByteBuffer copy = ByteBuffer.allocateDirect(texels * 4).order(ByteOrder.LITTLE_ENDIAN);
                copy.put(0, rgba, 0, texels * 4);
                data.pixels = copy;
            }
            walk(data, new AlphaPlane(alpha, imported.width, imported.height, originVersion));
            data.walkMillis = (System.nanoTime() - walkStart) / 1e6;

            data.state = NativeModelState.create();
            if (!data.extract(Attributes.rest(rest))) {
                throw new Refusal("the native refuses the rest pose of the model");
            }
            data.renderBonesAtRest = data.state.getRenderBoneIndices().capacity();
            long scheduled = Integer.toUnsignedLong(data.state.getTotalVertexCount());
            data.state.invalidate();
            // getBoneInfo hands the cube count of a bone partition over in 16 bits; the vertex total of the schedule
            // is counted by the native on its own and tells when the walk above did not see every cube.
            if (scheduled != data.scheduledVerticesAtRest) {
                throw new Refusal("cube walk: the native schedules " + scheduled + " vertices for the rest pose, the cubes that could be read give "
                        + data.scheduledVerticesAtRest + " (a bone with 65536 or more cubes of one kind cannot be read)");
            }
            data.totalMillis = (System.nanoTime() - start) / 1e6;
            complete = true;
            return data;
        } catch (RuntimeException problem) {
            throw new Refusal(NativeLoader.describe(problem));
        } finally {
            if (!complete) {
                data.close();
            }
        }
    }

    // Cube counts come from getBoneInfo; quad counts come from the cube data, because the vertex counts of
    // getBoneInfo are cut to 8 bits.
    private static void walk(ModelData data, AlphaPlane alpha) throws Refusal {
        int boneCount = data.boneCount;
        NativeBakedModel.BoneInfo[] infos = data.baked.getBoneInfo(0, boneCount);
        BoneMesh[] meshes = new BoneMesh[boneCount];
        long walked = 0;
        for (int bone = 0; bone < boneCount; bone++) {
            int[] cubeCounts = {infos[bone].cutout().cubeCount(), infos[bone].cutoutNoCulling().cubeCount(),
                    infos[bone].translucent().cubeCount(), infos[bone].translucentCulling().cubeCount()};
            BoneMesh.Builder builder = new BoneMesh.Builder();
            for (int partition = 0; partition < 4; partition++) {
                if (cubeCounts[partition] == 0) {
                    continue;
                }
                boolean culled = partition == BoneMesh.CUTOUT || partition == BoneMesh.TRANSLUCENT_CULLING;
                for (NativeBakedModel.CubeData cube : data.baked.getCubeData(bone, partition, 0, cubeCounts[partition])) {
                    walked += 4L * (culled ? cube.quadCountAfterCulling() : cube.quadCount());
                    Vector3f[] positions = cube.positions();
                    for (NativeBakedModel.QuadData quad : cube.quads()) {
                        int offset = builder.reserve();
                        float[] target = builder.data();
                        int[] corners = {quad.vertex0(), quad.vertex1(), quad.vertex2(), quad.vertex3()};
                        Vector2f[] uv = quad.uv();
                        for (int corner = 0; corner < 4; corner++) {
                            if (corners[corner] > 7) {
                                throw new Refusal("cube data: vertex index out of range");
                            }
                            Vector3f position = positions[corners[corner]];
                            target[offset + corner * 3] = position.x;
                            target[offset + corner * 3 + 1] = position.y;
                            target[offset + corner * 3 + 2] = position.z;
                            target[offset + 12 + corner * 2] = uv[corner].x;
                            target[offset + 12 + corner * 2 + 1] = uv[corner].y;
                            if (uv[corner].x < 0 || uv[corner].x > 1 || uv[corner].y < 0 || uv[corner].y > 1) {
                                data.uvOutOfRange++;
                            }
                        }
                        Vector3f normal = quad.normal();
                        target[offset + 20] = normal.x;
                        target[offset + 21] = normal.y;
                        target[offset + 22] = normal.z;
                        for (int index = offset; index < offset + BoneMesh.FLOATS_PER_QUAD; index++) {
                            if (!Float.isFinite(target[index])) {
                                throw new Refusal("cube data: a position, UV or normal is not finite");
                            }
                        }
                        float sign = quad.windingSign();
                        if (Float.isNaN(sign)) {
                            throw new Refusal("cube data: winding sign is not a number");
                        }
                        boolean partly = false;
                        if (partition >= BoneMesh.TRANSLUCENT_PARTITION) {
                            partly = alpha.classify(target, offset + 12) == AlphaPlane.TRANSLUCENT;
                            if (partly) {
                                data.translucentPartitionPartial++;
                            } else {
                                data.translucentPartitionBinary++;
                            }
                        }
                        settle(target, offset + 12, data.textureHeight);
                        builder.commit(partition, sign, partly);
                        data.partitionQuads[partition]++;
                        if (sign < 0) {
                            data.negativeSignQuads++;
                        } else if (sign == 0) {
                            data.zeroSignQuads++;
                        }
                        if (Math.abs(normal.length() - 1.0f) > 0.01f) {
                            data.nonUnitNormals++;
                        }
                    }
                }
            }
            if (builder.count() > 0) {
                BoneMesh mesh = builder.build();
                meshes[bone] = mesh;
                data.bonesWithGeometry++;
                data.emittedQuads[BoneMesh.OPAQUE] += mesh.emittedQuads(BoneMesh.OPAQUE);
                data.emittedQuads[BoneMesh.TRANSLUCENT] += mesh.emittedQuads(BoneMesh.TRANSLUCENT);
                data.droppedQuads += mesh.droppedQuads();
            }
        }
        long baked = data.partitionQuads[0] + data.partitionQuads[1] + data.partitionQuads[2] + data.partitionQuads[3];
        long kept = 0;
        for (BoneMesh mesh : meshes) {
            if (mesh != null) {
                kept += mesh.quadCount();
            }
        }
        if (kept != baked) {
            throw new Refusal("cube walk: quad counts do not add up");
        }
        data.meshes = meshes;
        data.scheduledVerticesAtRest = walked;
    }

    /**
     * A quad whose texture coordinates have no height sits exactly on the line between two texel rows (the thin faces
     * of thin cubes), and sampling the nearest texel there gives either row, pixel by pixel. Yes Steve Model 2.6.5
     * shows the row above the line, which is also where the bake rules from origin version 28 on move such a quad:
     * the V coordinate goes to the middle of that row. Coordinates without width are left as they are; for them
     * nothing is known about 2.6.5 beyond the official rule. uv: four corners, u then v, starting at offset.
     */
    static void settle(float[] uv, int offset, int height) {
        float value = uv[offset + 1];
        if (value != uv[offset + 3] || value != uv[offset + 5] || value != uv[offset + 7]) {
            return;
        }
        float texels = value * height;
        int line = Math.round(texels);
        if (Math.abs(texels - line) > 1.0e-3f) {
            return;
        }
        float middle = (Math.max(0, Math.min(line, height) - 1) + 0.5f) / height;
        for (int corner = 0; corner < 4; corner++) {
            uv[offset + corner * 2 + 1] = middle;
        }
    }

    /** The first rule of the native bone validation (baked_model.cc, BuildBoneHierarchy) the list breaks, or null. */
    private static String precheck(Proto.GeoModel geo) {
        List<Proto.Bone> bones = geo.bones;
        int count = bones.size();
        if (count > 65536) {
            return "too many bones";
        }
        Map<String, Integer> byName = new HashMap<>(count * 2);
        long cubeSum = 0;
        for (int index = 0; index < count; index++) {
            Proto.Bone bone = bones.get(index);
            // A name that is not UTF-8 is a name to the native; here it cannot be compared with others.
            if (!bone.nameUnreadable) {
                if (bone.name.isEmpty()) {
                    return "a bone has an empty name";
                }
                if (byName.put(bone.name, index) != null) {
                    return "two bones have the same name";
                }
            }
            boolean finite = bone.pivotCount == 3 && bone.rotateCount == 3;
            for (int axis = 0; axis < 3; axis++) {
                finite &= Float.isFinite(bone.pivot[axis]) && Float.isFinite(bone.rotate[axis]);
            }
            if (!finite) {
                return "a bone pivot or rotation is not three finite numbers";
            }
            cubeSum += Integer.toUnsignedLong(bone.cubeCount);
        }
        if (cubeSum != geo.cubes) {
            return "the bone cube counts do not add up to the cube list";
        }
        int[] parents = new int[count];
        for (int index = 0; index < count; index++) {
            String parent = bones.get(index).parent;
            if (parent == null || parent.isEmpty()) {
                parents[index] = -1;
                continue;
            }
            Integer found = byName.get(parent);
            if (found == null) {
                return "a bone names a parent that does not exist";
            }
            parents[index] = found;
        }
        // Every bone is walked upwards once: a walk stops at a bone an earlier walk has cleared, and a walk that
        // meets its own trail has found a cycle.
        final byte onTrail = 1;
        final byte cleared = 2;
        byte[] state = new byte[count];
        for (int start = 0; start < count; start++) {
            int current = start;
            while (current >= 0 && state[current] == 0) {
                state[current] = onTrail;
                current = parents[current];
            }
            if (current >= 0 && state[current] == onTrail) {
                return "the bone hierarchy has a cycle";
            }
            for (current = start; current >= 0 && state[current] == onTrail; current = parents[current]) {
                state[current] = cleared;
            }
        }
        return null;
    }

    private static float validScale(float value) {
        return Float.isFinite(value) && value > 0.0f ? value : DEFAULT_SCALE;
    }

    private static int findPayload(LegacyImport source, String kind, int logicalId) {
        for (int index = 0; index < source.payloadCount(); index++) {
            if (source.payloadLogicalId(index) == logicalId && source.payloadKind(index).equals(kind)) {
                return index;
            }
        }
        return -1;
    }
}
