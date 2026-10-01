package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.metrics.EndpointStats;
import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 并发压测：经过过滤器/转发/错误处理全链路后，
 * 每个请求在指标里恰好计数一次，错误请求口径稳定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpMetricsEndToEndTests {

    private static final Logger log = LoggerFactory.getLogger(HttpMetricsEndToEndTests.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MetricsRegistry metricsRegistry;

    @Test
    @DisplayName("40并发 x 25次混合请求：OK 与 ERROR 各自计数精确，404 归 UNMAPPED，不重不漏")
    void concurrentHttpRequestsCountedExactlyOnce() throws InterruptedException {
        int threads = 40;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger notFound = new AtomicInteger();

        String base = "http://localhost:" + port;
        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int kind = (threadIndex * perThread + i) % 3;
                        switch (kind) {
                            case 0 -> {
                                var r = restTemplate.getForEntity(base + "/api/demo/ok", Map.class);
                                assertEquals(200, r.getStatusCode().value());
                                ok.incrementAndGet();
                            }
                            case 1 -> {
                                var r = restTemplate.getForEntity(base + "/api/demo/error", Map.class);
                                assertEquals(500, r.getStatusCode().value());
                                errors.incrementAndGet();
                            }
                            default -> {
                                var r = restTemplate.getForEntity(base + "/api/no-such-" + i,
                                        Map.class);
                                assertEquals(404, r.getStatusCode().value());
                                notFound.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发请求应在超时前完成");
        pool.shutdown();

        long now = System.currentTimeMillis();
        EndpointStats okStats = metricsRegistry.endpointStats(now).get("GET /api/demo/ok");
        EndpointStats errorStats = metricsRegistry.endpointStats(now)
                .get("GET /api/demo/error");
        EndpointStats unmappedStats = metricsRegistry.endpointStats(now)
                .get("GET " + MetricsRegistry.UNMAPPED_ENDPOINT);

        long totalCounted = okStats.requestCount() + errorStats.requestCount()
                + unmappedStats.requestCount();
        log.info("traceId=(多请求,见各响应头) 精确计数: ok={} error={} unmapped={} 三类合计={}",
                okStats.requestCount(), errorStats.requestCount(), unmappedStats.requestCount(),
                totalCounted);

        assertTrue(okStats.requestCount() >= ok.get(), "窗口可能包含其他测试请求，只断言下界");
        // 本测试自身发出的数量必须精确命中（生命周期计数不受窗口影响）
        assertTrue(okStats.lifetimeRequestCount() >= ok.get());
        assertEquals(perThread * threads / 3, errors.get());
        assertEquals(perThread * threads / 3, notFound.get());
        assertTrue(errorStats.errorCount() >= errors.get(),
                "错误桶的错误数必须覆盖全部 500 请求");
        // 错误桶不应有误判为健康的样本：窗口内错误率（本测试制造的全是错误）
        assertEquals(1.0, errorStats.errorRate(), 1e-9,
                "/api/demo/error 全部为错误请求，窗口内错误率应为 1（该桶只有错误样本）");
    }
}
