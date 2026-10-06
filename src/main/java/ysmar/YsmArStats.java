package ysmar;

import java.util.Arrays;
import java.util.Locale;

/** Counters of the submit path. Written and read on the render thread. */
public final class YsmArStats {
    public enum Rejection {
        NOT_READY("model not ready"),
        AR_MISSING("Accelerated Rendering absent or unusable"),
        AR_NOT_LOADED("AR core not loaded"),
        NOT_LEVEL("not rendering the level"),
        ENTITIES_OFF("AR entity acceleration off"),
        VANILLA_PIPELINE("AR pipeline set to vanilla"),
        NOT_ACCELERATED("buffer not accelerated"),
        PREWARMING("bone meshes still building"),
        BAD_ATTRIBUTES("attributes refused"),
        BAD_ARGUMENTS("caller arguments refused"),
        EXTRACT_FAILED("Extract failed"),
        EXCEPTION("exception");

        public final String label;

        Rejection(String label) {
            this.label = label;
        }
    }

    public static final class Window {
        public long submits;
        public long boneDraws;
        public long vertices;
        public long extractNanos;
        public long extractMaxNanos;
        public long loopNanos;
        public long loopMaxNanos;
        /** Bone meshes built ahead of the first draw, the time that took and the longest single stretch of it. */
        public long prebuiltMeshes;
        public long prebuildNanos;
        public long prebuildMaxNanos;
        public final long[] rejections = new long[Rejection.values().length];

        void add(Window other) {
            submits += other.submits;
            boneDraws += other.boneDraws;
            vertices += other.vertices;
            extractNanos += other.extractNanos;
            extractMaxNanos = Math.max(extractMaxNanos, other.extractMaxNanos);
            loopNanos += other.loopNanos;
            loopMaxNanos = Math.max(loopMaxNanos, other.loopMaxNanos);
            prebuiltMeshes += other.prebuiltMeshes;
            prebuildNanos += other.prebuildNanos;
            prebuildMaxNanos = Math.max(prebuildMaxNanos, other.prebuildMaxNanos);
            for (int index = 0; index < rejections.length; index++) {
                rejections[index] += other.rejections[index];
            }
        }

        void clear() {
            submits = 0;
            boneDraws = 0;
            vertices = 0;
            extractNanos = 0;
            extractMaxNanos = 0;
            loopNanos = 0;
            loopMaxNanos = 0;
            prebuiltMeshes = 0;
            prebuildNanos = 0;
            prebuildMaxNanos = 0;
            Arrays.fill(rejections, 0);
        }

        public long rejected() {
            long sum = 0;
            for (long count : rejections) {
                sum += count;
            }
            return sum;
        }

        /** submits = entity draws that went through AR completely (each render pass counts). */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append("submits=").append(submits)
                    .append(" boneDraws=").append(boneDraws)
                    .append(" vertices=").append(vertices)
                    .append(" extract_us mean=").append(micros(extractNanos, submits)).append(" max=").append(micros(extractMaxNanos, 1))
                    .append(" loop_us mean=").append(micros(loopNanos, submits)).append(" max=").append(micros(loopMaxNanos, 1))
                    .append(" rejected=").append(rejected());
            for (Rejection rejection : Rejection.values()) {
                if (rejections[rejection.ordinal()] > 0) {
                    text.append(" [").append(rejection.label).append(": ").append(rejections[rejection.ordinal()]).append(']');
                }
            }
            if (prebuiltMeshes > 0) {
                text.append(" prebuilt_meshes=").append(prebuiltMeshes)
                        .append(" prebuild_ms total=").append(String.format(Locale.ROOT, "%.1f", prebuildNanos / 1e6))
                        .append(" longest=").append(String.format(Locale.ROOT, "%.2f", prebuildMaxNanos / 1e6));
            }
            return text.toString();
        }

        private static String micros(long nanos, long divisor) {
            return divisor <= 0 ? "-" : String.format(Locale.ROOT, "%.1f", nanos / 1e3 / divisor);
        }
    }

    private static Window current = new Window();
    private static final Window TOTAL = new Window();
    private static Rejection last;

    private YsmArStats() {
    }

    public static void reject(Rejection rejection) {
        current.rejections[rejection.ordinal()]++;
        last = rejection;
    }

    /** Why the most recent submit was refused; null after one that went through. */
    public static Rejection lastRejection() {
        return last;
    }

    public static void prebuilt(int meshes, long nanos) {
        Window window = current;
        window.prebuiltMeshes += meshes;
        window.prebuildNanos += nanos;
        window.prebuildMaxNanos = Math.max(window.prebuildMaxNanos, nanos);
    }

    public static void submitted(int boneDraws, long vertices, long extractNanos, long loopNanos) {
        last = null;
        Window window = current;
        window.submits++;
        window.boneDraws += boneDraws;
        window.vertices += vertices;
        window.extractNanos += extractNanos;
        window.extractMaxNanos = Math.max(window.extractMaxNanos, extractNanos);
        window.loopNanos += loopNanos;
        window.loopMaxNanos = Math.max(window.loopMaxNanos, loopNanos);
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
        last = null;
    }
}
