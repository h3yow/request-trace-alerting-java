package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.config.TraceAlertProperties;
import com.github.h3yow.requesttracealertingjava.metrics.EndpointStats;
import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import com.github.h3yow.requesttracealertingjava.metrics.RequestSample;
import com.github.h3yow.requesttracealertingjava.metrics.SlidingWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标统计稳定性测试：
 * 高并发下计数不重不漏、错误/慢口径正确、接口键归桶有界、滑动窗口容量有界。
 */
class MetricsConcurrencyIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(MetricsConcurrencyIntegrationTests.class);

    private TraceAlertProperties props(int maxEndpoints, int capacity) {
        TraceAlertProperties properties = new TraceAlertProperties();
        properties.setWindowRetentionMs(60_000);
        properties.setMaxSamplesPerEndpoint(capacity);
        properties.setMaxEndpoints(maxEndpoints);
        properties.setSlowRequestThresholdMs(500);
        return properties;
    }

    @Test
    @DisplayName("16线程并发写同一接口：计数精确等于请求数，错误数精确")
    void concurrentExactCounting() throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry(props(16, 100_000));
        int threads = 16;
        int perThread = 500;
        int total = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errorsProduced = new AtomicInteger();

        long now = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        boolean error = (threadIndex * perThread + i) % 10 == 0;
                        if (error) {
                            errorsProduced.incrementAndGet();
                        }
                        boolean slow = (i % 7 == 0);
                        registry.record(new RequestSample("GET /api/demo/ok", now,
                                slow ? 600 : 10, error ? 500 : 200, error, slow,
                                "trace-concurrent"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        EndpointStats stats = registry.endpointStats(now).get("GET /api/demo/ok");
        log.info("traceId=trace-concurrent 并发统计: 预期请求={} 实际请求={} 预期错误={} 实际错误={}",
                total, stats.requestCount(), errorsProduced.get(), stats.errorCount());
        assertEquals(total, stats.requestCount(), "并发下请求数必须精确，不重不漏");
        assertEquals(total, stats.lifetimeRequestCount());
        assertEquals(errorsProduced.get(), stats.errorCount());
    }

    @Test
    @DisplayName("慢请求与错误请求按统一口径计数，平均/最大耗时正确")
    void slowAndErrorAggregation() {
        MetricsRegistry registry = new MetricsRegistry(props(16, 100_000));
        long now = 10_000L;
        registry.record(new RequestSample("GET /x", now - 1, 100, 200, false, false, "t1"));
        registry.record(new RequestSample("GET /x", now - 2, 500, 200, false, true, "t2")); // 恰好阈值
        registry.record(new RequestSample("GET /x", now - 3, 700, 500, true, true, "t3"));
        EndpointStats stats = registry.endpointStats(now).get("GET /x");
        log.info("traceId=t1 聚合: count={} error={} slow={} avg={} max={} 依据: status>=500判错, "
                        + "durationMs>=500判慢", stats.requestCount(), stats.errorCount(),
                stats.slowCount(), stats.avgDurationMs(), stats.maxDurationMs());
        assertEquals(3, stats.requestCount());
        assertEquals(1, stats.errorCount());
        assertEquals(2, stats.slowCount()); // 500 与 700
        assertEquals(700, stats.maxDurationMs());
    }

    @Test
    @DisplayName("接口键数量有上限，超限并入 __OTHER__；未匹配请求进入 __UNMAPPED__")
    void endpointKeysAreBounded() {
        int maxEndpoints = 4;
        MetricsRegistry registry = new MetricsRegistry(props(maxEndpoints, 100));
        long now = 1_000L;
        for (int i = 0; i < 20; i++) {
            registry.record(new RequestSample("GET /api/dynamic/" + UUID.randomUUID(), now, 10,
                    200, false, false, "trace-bounded"));
        }
        Map<String, EndpointStats> stats = registry.endpointStats(now);
        log.info("traceId=trace-bounded 接口桶数={}（上限{}，含OTHER）", stats.size(),
                maxEndpoints + 1);
        assertTrue(stats.size() <= maxEndpoints + 1, "内置 OTHER 桶也算在内");
        assertNotNull(stats.get(MetricsRegistry.OTHER_ENDPOINT));
        assertEquals(20, stats.values().stream().mapToLong(EndpointStats::requestCount).sum(),
                "并入 OTHER 不允许丢样本");

        // 模拟 MetricsFilter 对未匹配请求的真实归桶："GET __UNMAPPED__"
        registry.record(new RequestSample(
                "GET " + MetricsRegistry.UNMAPPED_ENDPOINT, now, 1, 404, false, false,
                "trace-unmapped"));
        assertNotNull(registry.endpointStats(now).get("GET " + MetricsRegistry.UNMAPPED_ENDPOINT));
    }

    @Test
    @DisplayName("滑动窗口容量有上界：超出 capacity 时淘汰最老样本")
    void windowCapacityIsBounded() {
        SlidingWindow window = new SlidingWindow(60_000, 100);
        long now = 5_000L;
        for (int i = 0; i < 500; i++) {
            window.add(new RequestSample("GET /x", now + i, 10, 200, false, false, "trace-cap"));
        }
        assertEquals(100, window.snapshot(now + 500).size());
        log.info("traceId=trace-cap 窗口样本数={}（容量100，淘汰最老）",
                window.snapshot(now + 500).size());
    }

    @Test
    @DisplayName("滑动窗口按时间过期：旧样本淘汰，总量不超过 容量 与 时间窗口 二者约束")
    void windowExpiresByTime() {
        SlidingWindow window = new SlidingWindow(1_000, 1_000_000);
        window.add(new RequestSample("GET /x", 1_000, 10, 200, false, false, "trace-expire"));
        window.add(new RequestSample("GET /x", 1_500, 10, 200, false, false, "trace-expire"));
        window.add(new RequestSample("GET /x", 1_900, 10, 200, false, false, "trace-expire"));
        assertEquals(2, window.snapshot(2_000).size(), "仅保留 (1000,2000] 内样本");
        log.info("traceId=trace-expire 过期后样本数={}（窗口1000ms）",
                window.snapshot(2_000).size());
    }
}
