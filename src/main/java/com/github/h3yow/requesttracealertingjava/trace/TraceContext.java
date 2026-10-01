package com.github.h3yow.requesttracealertingjava.trace;

/**
 * 当前线程的链路上下文，保存链路标识及其来源。
 *
 * <p>基于 {@link ThreadLocal}，异步场景通过 {@link #snapshot()} /
 * {@link #restore(TraceSnapshot)} 跨线程传递，用 {@link #clear()} 防止线程池串号。
 */
public final class TraceContext {

    /** 链路标识来源：上游传入 / 缺失时生成 / 上游值非法时重新生成。 */
    public enum Source {
        UPSTREAM,
        GENERATED_MISSING,
        GENERATED_INVALID
    }

    /** 上下文快照，用于跨线程传递。 */
    public record TraceSnapshot(String traceId, Source source) {
    }

    private static final ThreadLocal<TraceSnapshot> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId, Source source) {
        HOLDER.set(new TraceSnapshot(traceId, source));
    }

    public static String getTraceId() {
        TraceSnapshot snapshot = HOLDER.get();
        return snapshot == null ? null : snapshot.traceId();
    }

    public static Source getSource() {
        TraceSnapshot snapshot = HOLDER.get();
        return snapshot == null ? null : snapshot.source();
    }

    /** 抓取当前上下文快照，没有上下文时返回 null。 */
    public static TraceSnapshot snapshot() {
        return HOLDER.get();
    }

    /** 在当前线程恢复快照，null 表示清空。 */
    public static void restore(TraceSnapshot snapshot) {
        if (snapshot == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(snapshot);
        }
    }

    public static void clear() {
        HOLDER.remove();
    }
}
