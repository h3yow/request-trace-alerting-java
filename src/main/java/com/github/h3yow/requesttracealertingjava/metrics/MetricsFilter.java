package com.github.h3yow.requesttracealertingjava.metrics;

import com.github.h3yow.requesttracealertingjava.config.TraceAlertProperties;
import com.github.h3yow.requesttracealertingjava.trace.TraceFilter;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 在请求最外层计时并记录一次指标样本。
 *
 * <p>不重不漏的保证：
 * <ul>
 *   <li>仅在初始 {@link DispatcherType#REQUEST} 上工作，FORWARD / ERROR / ASYNC 分发直接放行；</li>
 *   <li>同步请求在 finally 中记录一次；</li>
 *   <li>Servlet 异步请求（控制器返回 CompletableFuture 等）在 {@link AsyncListener}
 *       的 onComplete / onError / onTimeout 中记录一次，ASYNC 重新分发被跳过；</li>
 *   <li>正常完成与异常路径用 {@code recorded} 标志互斥，恰好一次。</li>
 * </ul>
 *
 * <p>判定口径（全系统唯一，告警共用）：错误 = 最终状态码 &gt;= 500 或处理中抛异常；
 * 慢 = 总耗时 &gt;= {@code observability.slow-request-threshold-ms}（含异步处理耗时）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsFilter.class);

    /**
     * 处理器（如统一异常处理）确认该请求未匹配到业务接口时设置的标记。
     * 过滤器在 finally 阶段据此归入固定 UNMAPPED 桶，而不是使用原始 URI。
     */
    public static final String UNMATCHED_ATTRIBUTE = MetricsFilter.class.getName() + ".unmatched";

    private final MetricsRegistry registry;
    private final long slowThresholdMs;

    public MetricsFilter(MetricsRegistry registry, TraceAlertProperties properties) {
        this.registry = registry;
        this.slowThresholdMs = properties.getSlowRequestThresholdMs();
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // 不在 ERROR 分发上记录，保证 404/500 转发 /error 时不产生第二次计数
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 双保险：即使注册了额外分发类型，也只在初始请求上统计
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            chain.doFilter(request, response);
            return;
        }

        long startNanos = System.nanoTime();
        long startMs = System.currentTimeMillis();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (Exception ex) {
            failed = true;
            throw ex;
        } finally {
            if (request.isAsyncStarted()) {
                // 异步请求：实际处理尚未结束，注册回调，完成时再记录
                AsyncContext asyncContext = request.getAsyncContext();
                asyncContext.addListener(new RecordingAsyncListener(request, response, startNanos,
                        startMs, failed));
            } else {
                recordSample(request, response, startNanos, startMs, failed, null);
            }
        }
    }

    private void recordSample(HttpServletRequest request, HttpServletResponse response,
                              long startNanos, long startMs, boolean failed, Throwable asyncError) {
        long durationMs = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000);
        int status = response.getStatus();
        boolean error = failed || asyncError != null || status >= 500;
        boolean slow = durationMs >= slowThresholdMs;
        String endpoint = endpointKey(request);
        Object traceAttr = request.getAttribute(TraceFilter.ATTR_TRACE_ID);
        String traceId = traceAttr instanceof String id ? id : null;

        RequestSample sample = new RequestSample(endpoint, startMs, durationMs, status, error,
                slow, traceId);
        registry.record(sample);
        log.info("记录指标 traceId={} endpoint={} status={} durationMs={} error={} slow={} "
                        + "判定依据: status>=500或抛异常 => error; durationMs>={} => slow",
                traceId, endpoint, status, durationMs, error, slow, slowThresholdMs);
    }

    /**
     * 接口维度键：HTTP 方法 + Spring 匹配到的 URI 模板（如 {@code GET /api/demo/{id}}）。
     * 未匹配到处理器（404 等）使用固定桶 {@link MetricsRegistry#UNMAPPED_ENDPOINT}，
     * 绝不使用原始 URI，防止任意路径撑爆键空间。
     */
    static String endpointKey(HttpServletRequest request) {
        if (request.getAttribute(UNMATCHED_ATTRIBUTE) == Boolean.TRUE) {
            return request.getMethod() + " " + MetricsRegistry.UNMAPPED_ENDPOINT;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String p && !p.isBlank()) {
            return request.getMethod() + " " + p;
        }
        return request.getMethod() + " " + MetricsRegistry.UNMAPPED_ENDPOINT;
    }

    private final class RecordingAsyncListener implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final long startNanos;
        private final long startMs;
        private final boolean failedBeforeAsync;
        private volatile boolean recorded;

        private RecordingAsyncListener(HttpServletRequest request, HttpServletResponse response,
                                       long startNanos, long startMs, boolean failedBeforeAsync) {
            this.request = request;
            this.response = response;
            this.startNanos = startNanos;
            this.startMs = startMs;
            this.failedBeforeAsync = failedBeforeAsync;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            if (recorded) {
                return;
            }
            recorded = true;
            recordSample(request, response, startNanos, startMs, failedBeforeAsync,
                    event.getThrowable());
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            if (recorded) {
                return;
            }
            recorded = true;
            // 超时通常以 503 结束；异步异常按错误请求处理
            recordSample(request, response, startNanos, startMs, failedBeforeAsync,
                    event.getThrowable() != null ? event.getThrowable()
                            : new AsyncTimeoutException());
        }

        @Override
        public void onError(AsyncEvent event) {
            if (recorded) {
                return;
            }
            recorded = true;
            recordSample(request, response, startNanos, startMs, true, event.getThrowable());
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // 不需要处理
        }
    }

    /** 仅用于日志/判定的异步超时标记异常。 */
    private static final class AsyncTimeoutException extends Exception {
        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
