package com.github.h3yow.requesttracealertingjava.metrics;

import com.github.h3yow.requesttracealertingjava.alert.AlertManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 指标拦截器：统计每个接口的请求量、错误数与耗时，并驱动告警评估。
 *
 * <p>计数口径：
 * <ul>
 *   <li>每个请求只统计一次——首次 preHandle 时在请求属性里落标记，
 *       内部转发、异步分发再次进入时跳过；记录发生在最内层 afterCompletion，
 *       记录后移除标记，外层 afterCompletion 不再重复记录；</li>
 *   <li>接口归属：首次 preHandle 时固化 URI 模板（如 /api/items/{id}），
 *       转发到其它接口不会改变本次请求的归属，也避免路径参数撑爆接口基数；</li>
 *   <li>错误：响应状态码 &ge; 500，或处理过程中抛出未捕获异常；</li>
 *   <li>耗时：从首次 preHandle 到记录时刻的墙钟时间。</li>
 * </ul>
 */
public class MetricsInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MetricsInterceptor.class);
    private static final String START_ATTR = MetricsInterceptor.class.getName() + ".START_NANOS";
    private static final String ENDPOINT_ATTR = MetricsInterceptor.class.getName() + ".ENDPOINT";

    private final MetricsRegistry registry;
    private final AlertManager alertManager;

    public MetricsInterceptor(MetricsRegistry registry, AlertManager alertManager) {
        this.registry = registry;
        this.alertManager = alertManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 首次进入（REQUEST 分发）才落标记；转发/异步分发时属性已存在，直接跳过
        if (request.getAttribute(START_ATTR) == null) {
            request.setAttribute(START_ATTR, System.nanoTime());
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String path = pattern != null ? pattern.toString() : request.getRequestURI();
            request.setAttribute(ENDPOINT_ATTR, request.getMethod() + " " + path);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object start = request.getAttribute(START_ATTR);
        if (start == null) {
            // 外层分发的 afterCompletion：标记已被最内层消费，不重复计数
            return;
        }
        request.removeAttribute(START_ATTR);
        String endpoint = (String) request.getAttribute(ENDPOINT_ATTR);
        request.removeAttribute(ENDPOINT_ATTR);

        long latencyMillis = (System.nanoTime() - (long) start) / 1_000_000;
        boolean error = ex != null || response.getStatus() >= 500;
        registry.record(endpoint, latencyMillis, error);
        alertManager.record(endpoint, System.currentTimeMillis(), latencyMillis, error);
        log.debug("request counted: endpoint={} latencyMs={} error={}", endpoint, latencyMillis, error);
    }
}
