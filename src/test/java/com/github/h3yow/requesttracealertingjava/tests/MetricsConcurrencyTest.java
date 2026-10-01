package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.metrics.EndpointMetrics;
import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发下的指标计数：不多计、不漏计，内存有上界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsConcurrencyTest.class);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private MetricsRegistry metricsRegistry;

    @Test
    void concurrentRequestsAreCountedExactlyOnce() throws Exception {
        String endpoint = "GET /api/hello";
        long before = metricsRegistry.get(endpoint) == null ? 0 : metricsRegistry.get(endpoint).getTotalCount();

        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                int ok = 0;
                for (int i = 0; i < perThread; i++) {
                    if (rest.getForEntity("/api/hello", String.class).getStatusCode().is2xxSuccessful()) {
                        ok++;
                    }
                }
                return ok;
            }));
        }
        ready.await();
        go.countDown();
        int succeeded = 0;
        for (Future<Integer> f : futures) {
            succeeded += f.get();
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long after = metricsRegistry.get(endpoint).getTotalCount();
        log.info("concurrent counting: succeeded={} countedDelta={} (before={} after={})",
                succeeded, after - before, before, after);
        assertThat(succeeded).isEqualTo(threads * perThread);
        // 每个成功请求恰好计一次：不多不少
        assertThat(after - before).isEqualTo(succeeded);
    }

    @Test
    void registryCountsAccuratelyUnderDirectConcurrency() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        int threads = 8;
        int perThread = 10_000;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> tasks = IntStream.range(0, threads)
                    .mapToObj(t -> (Callable<Void>) () -> {
                        for (int i = 0; i < perThread; i++) {
                            registry.record("GET /bench", i % 100, i % 3 == 0);
                        }
                        return null;
                    })
                    .toList();
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        }
        EndpointMetrics m = registry.get("GET /bench");
        log.info("registry concurrency: total={} errors={} avgLatencyMs={}",
                m.getTotalCount(), m.getErrorCount(), m.getAvgLatencyMillis());
        assertThat(m.getTotalCount()).isEqualTo((long) threads * perThread);
        assertThat(m.getErrorCount()).isEqualTo((long) threads * ((perThread + 2) / 3));
    }

    @Test
    void registryMemoryIsBoundedByOverflowBucket() {
        MetricsRegistry registry = new MetricsRegistry(4);
        for (int i = 0; i < 1_000; i++) {
            registry.record("GET /ep/" + i, 1, false);
        }
        int tracked = registry.snapshot().size();
        log.info("bounded registry: 1000 distinct endpoints -> {} tracked (keys={})",
                tracked, registry.snapshot().keySet());
        // 4 个独立接口 + 1 个 overflow 桶，内存有上界
        assertThat(tracked).isEqualTo(5);
        assertThat(registry.snapshot()).containsKey(MetricsRegistry.OVERFLOW_KEY);
        assertThat(registry.get(MetricsRegistry.OVERFLOW_KEY).getTotalCount()).isEqualTo(996);
    }
}
