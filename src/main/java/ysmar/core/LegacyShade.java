package ysmar.core;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * How Yes Steve Model 2.6.5 shades a bone whose scale differs between the axes.
 *
 * Its normal matrix follows the rule PoseStack.scale of the game had before 1.20.5: such a scale multiplies the
 * normal by the inverse scale times the cube root of the product of the three factors, a scale that is the same
 * on all axes leaves it alone, and the result is not brought back to length 1. The normal of a face so keeps the
 * direction Extract gives it, at another length, and the entity lighting of the game, which takes the normal as it
 * comes, makes the face brighter or darker. Accelerated Rendering normalises every normal it transforms, so the
 * length cannot be handed over. Instead the quads of such a bone that share a normal are drawn as one part, with
 * a normal the game lights fully and the shade 2.6.5 would have got in the colour.
 */
public final class LegacyShade {
    private static final float AMBIENT = 0.4f;
    private static final float POWER = 0.6f;
    // The first light of Lighting.setupLevel and setupNetherLevel; the second one is (-x, y, -z), in the nether its opposite.
    private static final float LIGHT_X = 0.2f / 1.2369317f;
    private static final float LIGHT_Y = 1.0f / 1.2369317f;
    private static final float LIGHT_Z = -0.7f / 1.2369317f;

    private LegacyShade() {
    }

    /**
     * What the normal Extract hands out for a bone has to be multiplied by to get the normal of 2.6.5: the cube root
     * of the product of all scale factors along the chain that differ between the axes. bonePose: the pose of the
     * bone; uniformScale: the product of the scales along the chain that are the same on all axes.
     */
    public static float length(Matrix4f bonePose, float uniformScale) {
        return (float) Math.cbrt(Math.abs(bonePose.determinant3x3())) / uniformScale;
    }

    /**
     * The shade, 0 to 255, the entity lighting of the game gives a normal that is stored as three signed bytes.
     * constantAmbient: the level is lit like the nether.
     */
    public static int shade(float x, float y, float z, boolean constantAmbient) {
        x = stored(x);
        y = stored(y);
        z = stored(z);
        float first = LIGHT_X * x + LIGHT_Y * y + LIGHT_Z * z;
        float second = constantAmbient ? -first : -LIGHT_X * x + LIGHT_Y * y - LIGHT_Z * z;
        return Math.round(255.0f * Math.min(1.0f, (Math.max(0.0f, first) + Math.max(0.0f, second)) * POWER + AMBIENT));
    }

    private static float stored(float value) {
        return Math.max(-127, Math.min(127, Math.round(value * 127.0f))) / 127.0f;
    }

    /** A normal matrix that turns the given unit normal into the direction of the first light, which is lit fully in every level. */
    public static Matrix3f towardsLight(float x, float y, float z, Matrix3f destination) {
        return destination.set(LIGHT_X * x, LIGHT_Y * x, LIGHT_Z * x, LIGHT_X * y, LIGHT_Y * y, LIGHT_Z * y, LIGHT_X * z, LIGHT_Y * z, LIGHT_Z * z);
    }
}
