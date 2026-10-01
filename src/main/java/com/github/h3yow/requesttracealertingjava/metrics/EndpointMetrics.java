package com.github.h3yow.requesttracealertingjava.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单个接口的累计指标：请求量、错误数、耗时。
 *
 * <p>计数用 {@link LongAdder} 扛高并发；最大值用 {@link AtomicLong} CAS 更新。
 * 每个实例的字段数固定，内存占用为 O(1)。
 */
public class EndpointMetrics {

    private final LongAdder totalCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder totalLatencyMillis = new LongAdder();
    private final AtomicLong maxLatencyMillis = new AtomicLong();

    public void record(long latencyMillis, boolean error) {
        totalCount.increment();
        totalLatencyMillis.add(latencyMillis);
        if (error) {
            errorCount.increment();
        }
        maxLatencyMillis.accumulateAndGet(latencyMillis, Math::max);
    }

    public long getTotalCount() {
        return totalCount.sum();
    }

    public long getErrorCount() {
        return errorCount.sum();
    }

    public long getTotalLatencyMillis() {
        return totalLatencyMillis.sum();
    }

    public long getMaxLatencyMillis() {
        return maxLatencyMillis.get();
    }

    /** 平均耗时（毫秒），无请求时为 0。 */
    public double getAvgLatencyMillis() {
        long total = getTotalCount();
        return total == 0 ? 0.0 : (double) getTotalLatencyMillis() / total;
    }

    /** 错误率，无请求时为 0。 */
    public double getErrorRate() {
        long total = getTotalCount();
        return total == 0 ? 0.0 : (double) getErrorCount() / total;
    }
}
