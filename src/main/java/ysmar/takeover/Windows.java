package ysmar.takeover;

/**
 * The renders of Yes Steve Model renderers that are under way, innermost first. One render ("window") runs from its
 * Pre event to its Post event; renders nest when a layer draws another entity.
 *
 * A window whose body may be taken over is decided at the visibility test (DECIDE). When the body is to be drawn as
 * late as Yes Steve Model reads its own values, the window is armed there and drawn (EMIT) at the first of:
 *   AT_SCALE  the scale call of the pre-render step, on the window's own pose stack with the model's own scale;
 *   AT_POST   the Post event of the window, when that call never came;
 *   AT_CLOSE  the moment the window stops being the open one for another reason: a later Pre event, the front-end
 *             switching off, a reset.
 * An armed window is emitted exactly once. One that is still armed when the next frame begins was never drawn; that
 * is reported as lost. Only the innermost window can be armed: every Pre event emits what is armed before another
 * window opens. Nothing here names a game class; the game side is the Stage. Render thread only.
 */
public final class Windows {
    public static final int MAX_DEPTH = 8;

    public static final int AT_SCALE = 0;
    public static final int AT_POST = 1;
    public static final int AT_CLOSE = 2;

    /** What DECIDE answers. */
    public enum Decision {
        /** The body stays with Yes Steve Model. */
        LEAVE,
        /** Hidden from Yes Steve Model and drawn later, at one of the three points. */
        ARM,
        /** Drawn later like ARM, but Yes Steve Model draws its own body too (the side-by-side comparison). */
        ARM_SHOWN,
        /** Hidden from Yes Steve Model and drawn already, inside DECIDE. */
        DRAWN
    }

    public static class Window {
        public Object renderer;
        public Object entity;
        public Object stack;
        /** The body may be taken over; false once anything spoke against it or another render started. */
        public boolean open;
        /** A render of the level: timed and counted. */
        public boolean timed;
        public long startNanos;
        /** Set by DECIDE for a window it arms: the scale the pre-render step of Yes Steve Model will apply. */
        public float width;
        public float height;
        /** The body went to Accelerated Rendering completely. */
        public boolean drawn;
        /** Yes Steve Model draws its own body as well. */
        public boolean shown;

        boolean decided;
        boolean hidden;
        boolean armed;
        Window outer;
        int depth;

        public boolean isArmed() {
            return armed;
        }

        /** What the visibility test was answered: true, the body is hidden from Yes Steve Model. */
        public boolean isHidden() {
            return hidden;
        }
    }

    /** The game side: what DECIDE and EMIT do, and what happens when something goes wrong. */
    public interface Stage {
        Decision decide(Window window) throws Exception;

        /** lateStack: at AT_SCALE the pose stack whose scale call has just returned, else null. */
        void emit(Window window, int point, Object lateStack) throws Exception;

        /** The window was closed by its own Post event. */
        void finished(Window window);

        /** An armed window was never emitted before the frame ended: its body was not drawn. */
        void lost(Window window);

        /** DECIDE or EMIT threw. Nothing is decided or armed after this returns unless the stage allows it again. */
        void failed(Throwable problem);
    }

    private final Stage stage;
    private Window current;
    private int armedWindows;

    private long hookCalls;
    private final long[] emitted = new long[3];
    private long lost;

    public Windows(Stage stage) {
        this.stage = stage;
    }

    /** Every Pre event of a living entity, also a cancelled one: no window stays open or armed past it. */
    public void closeAll() {
        for (Window window = current; window != null; window = window.outer) {
            emit(window, AT_CLOSE, null);
            window.open = false;
        }
    }

    /** The Pre event of a Yes Steve Model renderer. */
    public void open(Window window) {
        closeAll();
        Window outer = current;
        if (outer != null && outer.depth + 1 < MAX_DEPTH) {
            window.outer = outer;
            window.depth = outer.depth + 1;
        }
        current = window;
    }

    /**
     * The visibility test of the innermost window. True: the caller answers "not visible", and this mod has drawn
     * the body or is bound to draw it.
     */
    public boolean hide(Object renderer, Object entity) {
        Window window = current;
        if (window == null || window.renderer != renderer || window.entity != entity) {
            return false;
        }
        if (window.decided) {
            return window.hidden;
        }
        window.decided = true;
        hookCalls++;
        if (!window.open) {
            return false;
        }
        try {
            Decision decision = stage.decide(window);
            switch (decision) {
                case ARM, ARM_SHOWN -> {
                    window.shown = decision == Decision.ARM_SHOWN;
                    window.hidden = !window.shown;
                    window.armed = true;
                    armedWindows++;
                    LateHook.armed = true;
                }
                case DRAWN -> {
                    window.drawn = true;
                    window.hidden = true;
                }
                default -> {
                }
            }
        } catch (Throwable problem) {
            // What Accelerated Rendering already has must not be drawn a second time by Yes Steve Model.
            window.hidden = window.drawn && !window.shown;
            fail(problem);
        }
        return window.hidden;
    }

    /** A PoseStack.scale call has returned. Emits the innermost window when it is armed and this is its call. */
    public void scale(Object stack, float x, float y, float z) {
        Window window = current;
        if (window == null || !window.armed || window.stack != stack || !same(x, window.width) || !same(y, window.height)
                || !same(z, window.width)) {
            return;
        }
        emit(window, AT_SCALE, stack);
    }

    /** A Post event. ysmRenderer: it comes from a renderer of Yes Steve Model. */
    public void post(Object renderer, Object entity, boolean ysmRenderer) {
        Window window = current;
        if (window == null) {
            return;
        }
        if (window.renderer != renderer || window.entity != entity) {
            if (ysmRenderer) {
                // The end of a render that is not the open one: nothing that is still open can be trusted.
                clear();
            }
            return;
        }
        emit(window, AT_POST, null);
        current = window.outer;
        stage.finished(window);
    }

    /** A new frame begins: whatever is still armed was never drawn. */
    public void frame() {
        for (Window window = current; window != null; window = window.outer) {
            if (window.armed) {
                window.armed = false;
                lost++;
                stage.lost(window);
            }
        }
        armedWindows = 0;
        LateHook.armed = false;
        current = null;
    }

    /** Emits what is armed and forgets every window. */
    public void clear() {
        closeAll();
        current = null;
    }

    public void resetCounters() {
        hookCalls = 0;
        lost = 0;
        java.util.Arrays.fill(emitted, 0);
    }

    public long hookCalls() {
        return hookCalls;
    }

    /** Armed windows emitted at AT_SCALE, AT_POST or AT_CLOSE. */
    public long emitted(int point) {
        return emitted[point];
    }

    public long lost() {
        return lost;
    }

    public int armedWindows() {
        return armedWindows;
    }

    private void emit(Window window, int point, Object lateStack) {
        if (!window.armed) {
            return;
        }
        window.armed = false;
        armedWindows--;
        LateHook.armed = armedWindows > 0;
        emitted[point]++;
        try {
            stage.emit(window, point, lateStack);
        } catch (Throwable problem) {
            fail(problem);
        }
    }

    private void fail(Throwable problem) {
        try {
            stage.failed(problem);
        } finally {
            closeAll();
        }
    }

    private static boolean same(float value, float wanted) {
        return Float.floatToRawIntBits(value) == Float.floatToRawIntBits(wanted);
    }
}
