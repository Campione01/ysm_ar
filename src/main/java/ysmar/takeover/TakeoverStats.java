package ysmar.takeover;

import java.util.Arrays;
import java.util.Locale;

/**
 * Counters of the take-over front-end. One "render" is one run of a Yes Steve Model 2.6.5 renderer for one entity
 * in one render pass of the level. Written and read on the render thread.
 */
public final class TakeoverStats {
    /** Why a render stayed with the closed mod's own draw. */
    public enum Fallback {
        SWITCHED_OFF("takeover.enabled=false"),
        AR_OFF("Accelerated Rendering off or unusable"),
        NOT_LEVEL("outside level rendering"),
        GLOWING("entity appears glowing"),
        NO_ANIMATABLE("entity has no animatable"),
        MODEL_NOT_READY("2.6.5 model not ready"),
        NO_BINDING("no binding (see the binding list)"),
        LOADING("our copy of the model still loading"),
        PREWARMING("bone meshes still building"),
        ARRAY_SIZE("bone array of another size"),
        SLOT_11("bone_pivot_abs in use (slot 11)"),
        PIVOT_ARRAY("bone_pivot_abs array of 2.6.5 not usable"),
        SCALE("model scale not usable"),
        TEXTURE("texture not the model's own"),
        SUBMIT("submit refused (see rejected=)"),
        SIDE_BY_SIDE("side-by-side comparison (takeover.debug_side_by_side)");

        public final String label;

        Fallback(String label) {
            this.label = label;
        }
    }

    public static final int NOT_TAKEN = 0;
    public static final int TAKEN = 1;

    public static final class Window {
        /** Level renders of 2.6.5 renderers by outcome, and the time each took from its Pre to its Post event. */
        public final long[] renders = new long[2];
        public final long[] renderNanos = new long[2];
        public final long[] renderMaxNanos = new long[2];
        public final long[] fallbacks = new long[Fallback.values().length];
        /** Renders in which 2.6.5 itself draws nothing: the entity is invisible. */
        public long invisible;
        /** Renders outside the level (screens, the HUD, previews): never taken over, and no render in the counts above. */
        public long previews;
        /** Bodies drawn late, by the point they were drawn at (see Windows). */
        public final long[] emitted = new long[3];
        /** Hidden bodies that were never drawn, and ones whose draw failed. */
        public long lost;
        public long emitFailed;
        /** Late draws whose bone values or pose had changed after the visibility test; Extract calls that took. */
        public long lateBones;
        public long latePose;
        public long secondExtracts;
        public long copyDrawn;

        void add(Window other) {
            for (int kind = 0; kind < 2; kind++) {
                renders[kind] += other.renders[kind];
                renderNanos[kind] += other.renderNanos[kind];
                renderMaxNanos[kind] = Math.max(renderMaxNanos[kind], other.renderMaxNanos[kind]);
            }
            for (int index = 0; index < fallbacks.length; index++) {
                fallbacks[index] += other.fallbacks[index];
            }
            invisible += other.invisible;
            previews += other.previews;
            for (int point = 0; point < emitted.length; point++) {
                emitted[point] += other.emitted[point];
            }
            lost += other.lost;
            emitFailed += other.emitFailed;
            lateBones += other.lateBones;
            latePose += other.latePose;
            secondExtracts += other.secondExtracts;
            copyDrawn += other.copyDrawn;
        }

        void clear() {
            Arrays.fill(renders, 0);
            Arrays.fill(renderNanos, 0);
            Arrays.fill(renderMaxNanos, 0);
            Arrays.fill(fallbacks, 0);
            invisible = 0;
            previews = 0;
            Arrays.fill(emitted, 0);
            lost = 0;
            emitFailed = 0;
            lateBones = 0;
            latePose = 0;
            secondExtracts = 0;
            copyDrawn = 0;
        }

        public long fallbackCount() {
            long sum = 0;
            for (long count : fallbacks) {
                sum += count;
            }
            return sum;
        }

        /** Bodies drawn late at a point other than the scale call. */
        public long lateMissed() {
            return emitted[Windows.AT_POST] + emitted[Windows.AT_CLOSE];
        }

        /**
         * taken_over and not_taken are renders of the level; every fallback and every invisible entity is one of the
         * not_taken. previews are counted apart. pre_post_us: mean and largest time between the Pre and the Post
         * event of one render, by outcome.
         */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append("taken_over=").append(renders[TAKEN])
                    .append(" not_taken=").append(renders[NOT_TAKEN])
                    .append(" fallbacks=").append(fallbackCount());
            for (Fallback fallback : Fallback.values()) {
                if (fallbacks[fallback.ordinal()] > 0) {
                    text.append(" [").append(fallback.label).append(": ").append(fallbacks[fallback.ordinal()]).append(']');
                }
            }
            if (invisible > 0) {
                text.append(" invisible=").append(invisible);
            }
            if (previews > 0) {
                text.append(" previews=").append(previews);
            }
            long late = emitted[Windows.AT_SCALE] + lateMissed();
            if (late > 0 || lost > 0) {
                text.append(" late_read at_scale=").append(emitted[Windows.AT_SCALE]).append(" missed=").append(lateMissed());
                if (lateMissed() > 0) {
                    text.append(" (at Post ").append(emitted[Windows.AT_POST]).append(", at a close ").append(emitted[Windows.AT_CLOSE]).append(')');
                }
                text.append(" lost=").append(lost).append(" bones_changed=").append(lateBones).append(" pose_changed=").append(latePose)
                        .append(" second_extracts=").append(secondExtracts);
                if (copyDrawn > 0) {
                    text.append(" (earlier values drawn ").append(copyDrawn).append(')');
                }
            }
            if (emitFailed > 0) {
                text.append(" emit_failed=").append(emitFailed);
            }
            text.append(" pre_post_us taken mean=").append(micros(renderNanos[TAKEN], renders[TAKEN]))
                    .append(" max=").append(micros(renderMaxNanos[TAKEN], renders[TAKEN] > 0 ? 1 : 0))
                    .append(" not_taken mean=").append(micros(renderNanos[NOT_TAKEN], renders[NOT_TAKEN]))
                    .append(" max=").append(micros(renderMaxNanos[NOT_TAKEN], renders[NOT_TAKEN] > 0 ? 1 : 0));
            return text.toString();
        }

        private static String micros(long nanos, long divisor) {
            return divisor <= 0 ? "-" : String.format(Locale.ROOT, "%.1f", nanos / 1e3 / divisor);
        }
    }

    private static Window current = new Window();
    private static final Window TOTAL = new Window();

    private TakeoverStats() {
    }

    public static void fallback(Fallback reason) {
        current.fallbacks[reason.ordinal()]++;
    }

    public static void invisible() {
        current.invisible++;
    }

    public static void preview() {
        current.previews++;
    }

    /** A body was drawn late, at the point given. */
    public static void emitted(int point, boolean bonesChanged, boolean poseChanged, boolean extractedAgain, boolean copyDrawn) {
        Window window = current;
        window.emitted[point]++;
        window.lateBones += bonesChanged ? 1 : 0;
        window.latePose += poseChanged ? 1 : 0;
        window.secondExtracts += extractedAgain ? 1 : 0;
        window.copyDrawn += copyDrawn ? 1 : 0;
    }

    public static void lost() {
        current.lost++;
    }

    public static void emitFailed() {
        current.emitFailed++;
    }

    public static void rendered(boolean taken, long nanos) {
        int kind = taken ? TAKEN : NOT_TAKEN;
        Window window = current;
        window.renders[kind]++;
        window.renderNanos[kind] += nanos;
        window.renderMaxNanos[kind] = Math.max(window.renderMaxNanos[kind], nanos);
    }

    /** Ends the current window, adds it to the total and returns it. */
    public static Window roll() {
        Window finished = current;
        current = new Window();
        TOTAL.add(finished);
        return finished;
    }

    public static Window total() {
        Window sum = new Window();
        sum.add(TOTAL);
        sum.add(current);
        return sum;
    }

    public static void reset() {
        current = new Window();
        TOTAL.clear();
    }
}
