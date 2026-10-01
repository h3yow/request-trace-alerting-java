package com.github.h3yow.requesttracealertingjava.trace;

import org.slf4j.MDC;

/**
 * 基于 ThreadLocal 的当前请求链路上下文，并负责把标识写入/移出 MDC，
 * 使所有日志行自动带上 {@code traceId}（见 logback 配置中的 %X{traceId}）。
 *
 * <p>线程池线程会被复用，因此 {@link #bind} 与 {@link #clear} 必须严格配对，
 * 异步任务结束后必须清理，避免把上一个任务的标识串给下一个任务。
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";

    private record Holder(String traceId, TraceIdResolution resolution) {
    }

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 绑定当前线程的链路标识并写入 MDC。 */
    public static void bind(String traceId, TraceIdResolution resolution) {
        CURRENT.set(new Holder(traceId, resolution));
        MDC.put(MDC_KEY, traceId);
    }

    public static String currentTraceId() {
        Holder holder = CURRENT.get();
        return holder == null ? null : holder.traceId();
    }

    public static TraceIdResolution currentResolution() {
        Holder holder = CURRENT.get();
        return holder == null ? null : holder.resolution();
    }

    /** 解除绑定并清理 MDC，防止线程复用导致标识串用。 */
    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    /** 供异步线程使用的快照（只含不可变值）。 */
    public record Snapshot(String traceId, TraceIdResolution resolution) {
    }

    /** 在任务提交线程上抓取快照。 */
    public static Snapshot snapshot() {
        Holder holder = CURRENT.get();
        return holder == null ? null : new Snapshot(holder.traceId(), holder.resolution());
    }

    /** 在任务执行线程上恢复快照；snapshot 为 null 时不做绑定。 */
    public static void bindSnapshot(Snapshot snapshot) {
        if (snapshot != null) {
            bind(snapshot.traceId(), snapshot.resolution());
        }
    }
}
