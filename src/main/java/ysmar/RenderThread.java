package ysmar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;

/**
 * The rule that the model cache and the submitter are used from the render thread only. The client installs the
 * test for "this is the render thread"; a call from any other thread is refused, counted and logged once per kind.
 * Without a test (a plain JVM, where there is no render thread) every call is allowed.
 */
public final class RenderThread {
    public enum Call {
        REQUEST("YsmArModels.request"),
        CLEAR("YsmArModels.clear"),
        SUBMIT("YsmArSubmitter.submit");

        public final String label;

        Call(String label) {
            this.label = label;
        }
    }

    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final AtomicLongArray REFUSED = new AtomicLongArray(Call.values().length);
    private static volatile BooleanSupplier test;

    private RenderThread() {
    }

    /** onRenderThread: true on the render thread; null removes the rule. */
    public static void rule(BooleanSupplier onRenderThread) {
        test = onRenderThread;
    }

    /** True when the calling thread may make the call. */
    public static boolean allows(Call call) {
        BooleanSupplier current = test;
        if (current == null || current.getAsBoolean()) {
            return true;
        }
        if (REFUSED.getAndIncrement(call.ordinal()) == 0) {
            LOGGER.warn("ysm_ar: {} was called from thread \"{}\", not from the render thread: refused (further such calls are only counted)",
                    call.label, Thread.currentThread().getName());
        }
        return false;
    }

    public static long refused(Call call) {
        return REFUSED.get(call.ordinal());
    }

    public static String describe() {
        return "request=" + refused(Call.REQUEST) + " clear=" + refused(Call.CLEAR) + " submit=" + refused(Call.SUBMIT);
    }
}
