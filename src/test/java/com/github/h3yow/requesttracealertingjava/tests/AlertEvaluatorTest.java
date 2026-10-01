package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.alert.AlertEvent;
import com.github.h3yow.requesttracealertingjava.alert.AlertEvaluator;
import com.github.h3yow.requesttracealertingjava.alert.AlertRule;
import com.github.h3yow.requesttracealertingjava.alert.AlertSink;
import com.github.h3yow.requesttracealertingjava.alert.SlidingWindow;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警状态机：连续窗口触发、抑制窗口、恢复识别、规则更新语义、重评估确定性。
 *
 * <p>评估语义：每个 1 秒时间桶只在首次落入时评估一次，
 * “连续 N 次异常”指连续 N 个桶的评估都异常。
 */
class AlertEvaluatorTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluatorTest.class);

    /** 小规则：10 秒窗口，至少 4 个样本，错误率 > 0.5 或慢请求 > 2 为异常，连续 2 次触发，抑制 60 秒。 */
    private AlertRule smallRule() {
        return new AlertRule(10_000, 4, 0.5, 1_000, 2, 2, 60_000);
    }

    private static class CollectingSink implements AlertSink {
        final List<AlertEvent> events = new ArrayList<>();

        @Override
        public void emit(AlertEvent event) {
            log.info("alert event: type={} endpoint={} reason={} stats={}",
                    event.type(), event.endpoint(), event.reason(), event.stats());
            events.add(event);
        }
    }

    /** 在指定秒桶内记录 count 条错误请求。 */
    private void errorsInBucket(AlertEvaluator evaluator, long bucketStartMillis, int count) {
        for (int i = 0; i < count; i++) {
            evaluator.recordAndEvaluate(bucketStartMillis + i * 100, true, false);
        }
    }

    @Test
    void triggersOnlyAfterConsecutiveBreachingWindows() {
        CollectingSink sink = new CollectingSink();
        AlertEvaluator evaluator = new AlertEvaluator("GET /api/fail", smallRule(), sink);

        // 桶 1s：4 条错误（首次评估时样本不足，不异常）
        errorsInBucket(evaluator, 1_000, 4);
        // 桶 2s：第 1 次异常评估（窗口内 5 条全错），连续次数=1，不触发
        errorsInBucket(evaluator, 2_000, 1);
        assertThat(sink.events).isEmpty();

        // 桶 3s：连续第 2 次异常，触发
        errorsInBucket(evaluator, 3_000, 1);
        log.info("events after 2 consecutive breaches: {}", sink.events);
        assertThat(sink.events).hasSize(1);
        assertThat(sink.events.get(0).type()).isEqualTo(AlertEvent.Type.TRIGGERED);
        assertThat(sink.events.get(0).reason()).contains("errorRate");
    }

    @Test
    void suppressionWindowPreventsAlertStorm() {
        CollectingSink sink = new CollectingSink();
        AlertEvaluator evaluator = new AlertEvaluator("GET /api/fail", smallRule(), sink);

        // 触发：桶 1s/2s/3s 连续异常
        errorsInBucket(evaluator, 1_000, 4);
        errorsInBucket(evaluator, 2_000, 1);
        errorsInBucket(evaluator, 3_000, 1);
        assertThat(sink.events).hasSize(1);

        // 恢复：20s 时旧错误已滚出窗口，正常请求使评估恢复正常
        evaluator.recordAndEvaluate(20_000, false, false);
        evaluator.recordAndEvaluate(21_000, false, false);
        evaluator.recordAndEvaluate(22_000, false, false);
        evaluator.recordAndEvaluate(23_000, false, false);
        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(1).type()).isEqualTo(AlertEvent.Type.RECOVERED);

        // 抑制窗口内再次异常（距上次触发仅 ~28s < 60s）：不重复触发
        errorsInBucket(evaluator, 30_000, 4);
        errorsInBucket(evaluator, 31_000, 1);
        errorsInBucket(evaluator, 32_000, 1);
        log.info("events during suppression: {}", sink.events);
        assertThat(sink.events).hasSize(2);

        // 抑制窗口过后再次异常：重新触发
        errorsInBucket(evaluator, 100_000, 4);
        errorsInBucket(evaluator, 101_000, 1);
        errorsInBucket(evaluator, 102_000, 1);
        log.info("events after suppression expired: {}", sink.events);
        assertThat(sink.events).hasSize(3);
        assertThat(sink.events.get(2).type()).isEqualTo(AlertEvent.Type.TRIGGERED);
    }

    @Test
    void recoveryIsDetectedWhenWindowReturnsToNormal() {
        CollectingSink sink = new CollectingSink();
        AlertEvaluator evaluator = new AlertEvaluator("GET /api/fail", smallRule(), sink);

        errorsInBucket(evaluator, 1_000, 4);
        errorsInBucket(evaluator, 2_000, 1);
        errorsInBucket(evaluator, 3_000, 1);
        assertThat(sink.events).hasSize(1);

        // 旧错误滚出窗口后记录正常请求 → 恢复
        // 同一桶内 4 条正常请求（首次评估样本不足，状态不变），下一桶评估时样本充分 → 恢复
        for (int i = 0; i < 4; i++) {
            evaluator.recordAndEvaluate(100_000 + i * 100, false, false);
        }
        evaluator.recordAndEvaluate(101_000, false, false);
        log.info("events after recovery: {}", sink.events);
        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(1).type()).isEqualTo(AlertEvent.Type.RECOVERED);
        assertThat(sink.events.get(1).reason()).contains("within thresholds");
    }

    @Test
    void sampleTooSmallNeverTriggers() {
        CollectingSink sink = new CollectingSink();
        AlertEvaluator evaluator = new AlertEvaluator("GET /api/fail", smallRule(), sink);

        // 全部错误但窗口样本始终不足 minRequests=4（10 秒窗口内最多 3 条）
        errorsInBucket(evaluator, 1_000, 1);
        errorsInBucket(evaluator, 2_000, 1);
        errorsInBucket(evaluator, 3_000, 1);
        log.info("events with small sample: {}", sink.events);
        assertThat(sink.events).isEmpty();
    }

    @Test
    void slowRequestsTriggerBySlowCount() {
        CollectingSink sink = new CollectingSink();
        AlertEvaluator evaluator = new AlertEvaluator("GET /api/slow", smallRule(), sink);

        // 桶 1s：3 个慢请求（> maxSlowCount=2），但首次评估样本不足
        for (int i = 0; i < 3; i++) {
            evaluator.recordAndEvaluate(1_000 + i * 100, false, true);
        }
        evaluator.recordAndEvaluate(1_900, false, false);
        // 桶 2s、3s：连续两次慢请求数超标 → 触发
        evaluator.recordAndEvaluate(2_000, false, true);
        evaluator.recordAndEvaluate(3_000, false, false);
        log.info("events for slow requests: {}", sink.events);
        assertThat(sink.events).hasSize(1);
        assertThat(sink.events.get(0).type()).isEqualTo(AlertEvent.Type.TRIGGERED);
        assertThat(sink.events.get(0).reason()).contains("slowCount");
    }

    @Test
    void ruleUpdateOnlyAffectsFutureEvaluations() {
        CollectingSink sink = new CollectingSink();
        AlertEvaluator evaluator = new AlertEvaluator("GET /api/fail", smallRule(), sink);

        // 旧规则下触发
        errorsInBucket(evaluator, 1_000, 4);
        errorsInBucket(evaluator, 2_000, 1);
        errorsInBucket(evaluator, 3_000, 1);
        assertThat(sink.events).hasSize(1);

        // 更新为极宽松规则：之后的评估用新规则，同样的数据不再算异常 → 恢复
        AlertRule relaxed = new AlertRule(10_000, 4, 1.0, 1_000, 1_000, 2, 60_000);
        evaluator.updateRule(relaxed);
        evaluator.recordAndEvaluate(4_000, true, false);
        log.info("events after rule update: {}", sink.events);
        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(1).type()).isEqualTo(AlertEvent.Type.RECOVERED);

        // 历史结论不被改写：已产生的 TRIGGERED 事件保持不变
        assertThat(sink.events.get(0).type()).isEqualTo(AlertEvent.Type.TRIGGERED);
        assertThat(sink.events.get(0).reason()).contains("errorRate");
    }

    @Test
    void pureEvaluationIsDeterministicForSameDataAndRule() {
        AlertRule rule = smallRule();
        SlidingWindow.WindowStats stats = new SlidingWindow.WindowStats(10, 8, 0);
        AlertEvaluator.WindowSnapshot snapshot = new AlertEvaluator.WindowSnapshot(stats);

        AlertEvaluator.Decision first = AlertEvaluator.evaluate(snapshot, rule);
        AlertEvaluator.Decision second = AlertEvaluator.evaluate(snapshot, rule);
        log.info("deterministic decision: breach={} reason={}", first.breach(), first.reason());
        assertThat(first).isEqualTo(second);
        assertThat(first.breach()).isTrue();
        assertThat(first.reason()).contains("errorRate=0.800");
    }
}
