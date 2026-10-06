package ysmar;

/**
 * How long bone meshes may still be built in the frame at hand. The first draw of a model needs one mesh per bone
 * and vertex layout; building them all at once costs tens to hundreds of milliseconds. Render thread only.
 */
public final class PrewarmBudget {
    public static final long UNLIMITED = Long.MAX_VALUE;

    // Before the first frame was announced nothing is limited (the offline checks run without frames).
    private static long remainingNanos = UNLIMITED;

    private PrewarmBudget() {
    }

    /** Called once per frame. A budget of zero or less means no limit. */
    public static void newFrame(long budgetNanos) {
        remainingNanos = budgetNanos <= 0 ? UNLIMITED : budgetNanos;
    }

    public static long remaining() {
        return remainingNanos;
    }

    public static void spend(long nanos) {
        if (remainingNanos != UNLIMITED) {
            remainingNanos = Math.max(0L, remainingNanos - Math.max(0L, nanos));
        }
    }
}
