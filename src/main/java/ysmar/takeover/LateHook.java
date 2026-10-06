package ysmar.takeover;

/**
 * The one thing the mixin on PoseStack.scale reads on every call of that method, from whatever thread: true only
 * while a taken-over render waits for the scale call of Yes Steve Model's pre-render step.
 */
public final class LateHook {
    public static boolean armed;

    private LateHook() {
    }
}
