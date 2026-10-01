package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.alert.SlidingWindow;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 滑动窗口聚合：边界、确定性、内存上界。
 */
class SlidingWindowTest {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowTest.class);

    @Test
    void aggregatesOnlyWithinWindow() {
        SlidingWindow window = new SlidingWindow(12, 1_000);
        long now = 60_000;
        // 窗口 (50s, 60s] 之外的数据（按 1 秒桶对齐，桶 50000 正好在边界外）
        window.record(40_000, true, true);
        window.record(50_000, true, false);
        // 窗口内的数据
        window.record(51_000, false, false);
        window.record(55_000, true, false);
        window.record(59_999, false, true);

        SlidingWindow.WindowStats stats = window.aggregate(now, 10_000);
        log.info("window stats: {}", stats);
        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.slows()).isEqualTo(1);
    }

    @Test
    void reEvaluationOfSameHistoryIsDeterministic() {
        SlidingWindow window = new SlidingWindow(12, 1_000);
        long now = 100_000;
        window.record(95_000, true, false);
        window.record(96_000, false, true);
        window.record(97_000, true, true);

        SlidingWindow.WindowStats first = window.aggregate(now, 10_000);
        SlidingWindow.WindowStats second = window.aggregate(now, 10_000);
        log.info("deterministic re-evaluation: first={} second={}", first, second);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void oldBucketsAreReclaimedSoMemoryStaysBounded() {
        SlidingWindow window = new SlidingWindow(8, 1_000);
        // 写入远超桶数量的时间点，内存仍只有 8 个桶
        for (int i = 0; i < 10_000; i++) {
            window.record(i * 1_000L, i % 2 == 0, false);
        }
        SlidingWindow.WindowStats stats = window.aggregate(10_000_000L, 8_000);
        log.info("after 10000 records, last-window stats: {}", stats);
        assertThat(stats.total()).isLessThanOrEqualTo(9);
    }
}
