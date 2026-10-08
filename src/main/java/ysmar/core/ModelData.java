package ysmar.core;

import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.natives.render.NativeModelState;

import java.nio.ByteBuffer;
import java.util.List;

/** Everything a loaded model consists of. Bone indices are the bake's sorted order, the order of the attribute array. */
public final class ModelData {
    public int boneCount;
    public List<String> boneNames;
    /** Sorted index of the parent of each bone, -1 for a root. A parent always precedes its children. */
    public int[] parents;
    /** Static rotation of each bone, three floats per bone, as the attribute array expects it at rest. */
    public float[] restRotations;
    /** Pivot of each bone in model units (1/16 block), three floats per bone, as Extract uses it. */
    public float[] pivots;
    /**
     * The name this mod gave to the one bone whose name is empty in the file (the bake takes no empty name), or
     * null. boneNames holds that name; whoever matches bones by name maps the empty name to it.
     */
    public String emptyBoneName;
    /** One mesh per bone, null where the bone has no geometry. */
    public BoneMesh[] meshes;
    /** Bones that have a face exactly where another bone has one (BoneMesh.partners). */
    public int orderedBones;
    public float heightScale;
    public float widthScale;
    public List<String> textureKeys;
    public String textureKey;
    public int textureWidth;
    public int textureHeight;
    public boolean hasPbr;
    public boolean forceCulling;
    /** The origin version the bake ran with, and the one written in the file (-1: none). */
    public int originVersion;
    public int fileOriginVersion;
    public NativeBakedModel baked;
    /**
     * One state serves every entity that uses the model: Extract computes poses and render bones from its
     * arguments alone. What a state keeps between calls is a schedule cache for the native renderer, keyed by the
     * previous list of render bones; it changes the cost of a call, never its result.
     */
    public NativeModelState state;
    /** Counts the Extract calls on the state: whoever keeps poses of one call can tell that another one came since. */
    public long extracts;
    /** Decoded RGBA texels, top row first, when the caller asked to keep them; else null. */
    public ByteBuffer pixels;

    public final long[] partitionQuads = new long[4];
    public final long[] emittedQuads = new long[2];
    public long droppedQuads;
    public long negativeSignQuads;
    public long zeroSignQuads;
    public long nonUnitNormals;
    public long uvOutOfRange;
    public long translucentPartitionBinary;
    public long translucentPartitionPartial;
    public int bonesWithGeometry;
    public int renderBonesAtRest;
    /** Vertices the native schedules for the rest pose; the cube walk has to arrive at the same number. */
    public long scheduledVerticesAtRest;
    public boolean sortedIsIdentity;

    public double importMillis;
    public double decodeMillis;
    public double bakeMillis;
    public double readMillis;
    public double walkMillis;
    public double totalMillis;

    /** Every Extract of the model goes through here; the poses and render bones of the call before it are gone. */
    public boolean extract(float[] attributes) {
        extracts++;
        return state.extract(baked, attributes);
    }

    public long emittedVertices() {
        return 4 * (emittedQuads[0] + emittedQuads[1]);
    }

    public void close() {
        NativeModelState oldState = state;
        NativeBakedModel oldBaked = baked;
        state = null;
        baked = null;
        pixels = null;
        if (oldState != null) {
            oldState.close();
        }
        if (oldBaked != null) {
            oldBaked.close();
        }
    }
}
