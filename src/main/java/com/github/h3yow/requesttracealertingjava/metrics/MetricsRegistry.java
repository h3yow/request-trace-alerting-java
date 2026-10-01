package com.github.h3yow.requesttracealertingjava.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指标注册表：按接口维度登记指标。
 *
 * <p>内存上界：最多跟踪 {@value #DEFAULT_MAX_ENDPOINTS} 个接口，
 * 超出后新接口统一归入 {@value #OVERFLOW_KEY} 桶，
 * 因此接口数量不受控时内存占用仍有上界。
 */
public class MetricsRegistry {

    public static final int DEFAULT_MAX_ENDPOINTS = 1024;
    public static final String OVERFLOW_KEY = "__overflow__";

    private final ConcurrentHashMap<String, EndpointMetrics> metrics = new ConcurrentHashMap<>();
    private final AtomicInteger trackedEndpoints = new AtomicInteger();
    private final int maxEndpoints;

    public MetricsRegistry() {
        this(DEFAULT_MAX_ENDPOINTS);
    }

    public MetricsRegistry(int maxEndpoints) {
        this.maxEndpoints = maxEndpoints;
    }

    public void record(String endpoint, long latencyMillis, boolean error) {
        EndpointMetrics m = metrics.computeIfAbsent(endpoint, key -> {
            if (trackedEndpoints.incrementAndGet() > maxEndpoints) {
                trackedEndpoints.decrementAndGet();
                return null;
            }
            return new EndpointMetrics();
        });
        if (m == null) {
            m = metrics.computeIfAbsent(OVERFLOW_KEY, key -> new EndpointMetrics());
        }
        m.record(latencyMillis, error);
    }

    public EndpointMetrics get(String endpoint) {
        return metrics.get(endpoint);
    }

    /** 当前指标快照（只读视图，键的迭代顺序稳定）。 */
    public Map<String, EndpointMetrics> snapshot() {
        Map<String, EndpointMetrics> copy = new LinkedHashMap<>();
        metrics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> copy.put(e.getKey(), e.getValue()));
        return Collections.unmodifiableMap(copy);
    }
}
