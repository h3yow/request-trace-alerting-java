package com.github.h3yow.requesttracealertingjava.metrics;

import com.github.h3yow.requesttracealertingjava.config.TraceAlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按接口维度统计请求量、错误率、耗时。
 *
 * <p>并发与计数口径：
 * <ul>
 *   <li>每个 HTTP 请求只在 MetricsFilter 最外层记录一次（仅 REQUEST 分发），
 *       FORWARD / ERROR / 容器异步分发不再计数，因此不重不漏；</li>
 *   <li>各接口桶之间互不影响，使用 {@link ConcurrentHashMap} 承担并发；</li>
 *   <li>桶内部用 {@link SlidingWindow} 同一把锁，快照与写入串行，统计基于快照；</li>
 *   <li>错误定义（HTTP 状态码 &gt;= 500 或处理过程抛异常）、慢请求定义
 *       （耗时 &gt;= {@code observability.slow-request-threshold-ms}）全系统唯一，
 *       指标与告警共用同一口径。</li>
 * </ul>
 *
 * <p>内存上界：接口键数量上限 {@code maxEndpoints}，超限流量并入 {@code __OTHER__}；
 * 每接口窗口样本数上限 {@code maxSamplesPerEndpoint}，且按 {@code windowRetentionMs} 过期。
 */
@Component
public class MetricsRegistry {

    /** 接口数超限时的兜底桶，保证键空间有界。 */
    public static final String OTHER_ENDPOINT = "__OTHER__";
    /** 未匹配到处理器的请求（如 404）归入此桶，避免把原始 URI 当键导致键爆炸。 */
    public static final String UNMAPPED_ENDPOINT = "__UNMAPPED__";

    private static final Logger log = LoggerFactory.getLogger(MetricsRegistry.class);

    private final long retentionMs;
    private final int capacity;
    private final int maxEndpoints;

    private final ConcurrentHashMap<String, EndpointData> endpoints = new ConcurrentHashMap<>();

    public MetricsRegistry(TraceAlertProperties properties) {
        this.retentionMs = properties.getWindowRetentionMs();
        this.capacity = properties.getMaxSamplesPerEndpoint();
        this.maxEndpoints = properties.getMaxEndpoints();
    }

    private static final class EndpointData {
        private final SlidingWindow window;
        private long lifetimeCount;

        private EndpointData(long retentionMs, int capacity) {
            this.window = new SlidingWindow(retentionMs, capacity);
        }
    }

    /**
     * 记录一次请求。样本的 error/slow 判定由调用方（MetricsFilter）按统一口径给出，
     * 注册表不再二次解释状态码，避免口径分叉。
     */
    public void record(RequestSample sample) {
        String key = resolveKey(sample.endpoint());
        // computeIfAbsence 内的函数必须快速无锁；这里只用构造参数创建桶
        EndpointData data = endpoints.computeIfAbsent(key,
                k -> new EndpointData(retentionMs, capacity));
        synchronized (data) {
            data.lifetimeCount++;
            data.window.add(sample);
        }
    }

    /**
     * 决定样本归属桶：
     * 内置桶（{@link #OTHER_ENDPOINT} / {@link #UNMAPPED_ENDPOINT}，允许 "METHOD 前缀"
     * 形式，如 {@code "GET __UNMAPPED__"}）始终允许；
     * 普通新键达到上限时并入 {@link #OTHER_ENDPOINT}，保证键数量有界。
     */
    private String resolveKey(String requestedKey) {
        if (isBuiltInKey(requestedKey)) {
            return requestedKey;
        }
        if (endpoints.size() < maxEndpoints) {
            return requestedKey;
        }
        // 已达上限：已存在的桶继续使用，新键并入 OTHER
        if (endpoints.containsKey(requestedKey)) {
            return requestedKey;
        }
        log.warn("接口桶数量达到上限 maxEndpoints={}，endpoint={} 的指标并入 {}",
                maxEndpoints, requestedKey, OTHER_ENDPOINT);
        return OTHER_ENDPOINT;
    }

    private static boolean isBuiltInKey(String key) {
        return OTHER_ENDPOINT.equals(key) || UNMAPPED_ENDPOINT.equals(key)
                || key.endsWith(" " + OTHER_ENDPOINT)
                || key.endsWith(" " + UNMAPPED_ENDPOINT);
    }

    /** 淘汰所有桶内的过期样本。 */
    public void evictExpired(long nowMs) {
        endpoints.forEachValue(Long.MAX_VALUE, data -> data.window.prune(nowMs));
    }

    /** 各接口当前窗口统计快照。 */
    public Map<String, EndpointStats> endpointStats(long nowMs) {
        Map<String, EndpointStats> result = new LinkedHashMap<>();
        endpoints.forEach((key, data) -> {
            List<RequestSample> samples;
            long lifetime;
            synchronized (data) {
                samples = data.window.snapshot(nowMs);
                lifetime = data.lifetimeCount;
            }
            result.put(key, aggregate(key, samples, lifetime, nowMs));
        });
        return result;
    }

    /** 某接口当前窗口内的样本快照，供告警引擎做纯函数判定。 */
    public List<RequestSample> samplesOf(String endpoint, long nowMs) {
        EndpointData data = endpoints.get(endpoint);
        if (data == null) {
            return List.of();
        }
        synchronized (data) {
            return data.window.snapshot(nowMs);
        }
    }

    private static EndpointStats aggregate(String endpoint, List<RequestSample> samples,
                                           long lifetimeCount, long nowMs) {
        long errorCount = 0;
        long slowCount = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        long earliest = nowMs;
        for (RequestSample sample : samples) {
            if (sample.error()) {
                errorCount++;
            }
            if (sample.slow()) {
                slowCount++;
            }
            totalDuration += sample.durationMs();
            maxDuration = Math.max(maxDuration, sample.durationMs());
            earliest = Math.min(earliest, sample.startMs());
        }
        long count = samples.size();
        double errorRate = count == 0 ? 0.0 : (double) errorCount / count;
        double avgDuration = count == 0 ? 0.0 : (double) totalDuration / count;
        return new EndpointStats(endpoint, count, lifetimeCount, errorCount, slowCount,
                totalDuration, maxDuration, errorRate, avgDuration,
                count == 0 ? nowMs : earliest, nowMs);
    }
}
