package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.alert.AlertMetric;
import com.github.h3yow.requesttracealertingjava.alert.AlertRule;
import com.github.h3yow.requesttracealertingjava.alert.AlertVerdict;
import com.github.h3yow.requesttracealertingjava.metrics.RequestSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警纯函数判定测试：阈值边界、最小样本、错误/慢口径、窗口边界与重放一致性。
 * 每条判定都打印判定依据 reason，便于复核。
 */
class AlertEvaluationTests {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationTests.class);

    private static final String ENDPOINT = "GET /api/demo/error";
    private static final long NOW = 1_000_000L;

    private AlertRule rule(AlertMetric metric, double threshold, int minSamples, long windowMs) {
        return new AlertRule("r1", ENDPOINT, metric, threshold, minSamples, windowMs, 2, 60_000,
                1L);
    }

    private RequestSample sample(long offsetMs, boolean error, boolean slow) {
        return new RequestSample(ENDPOINT, NOW + offsetMs, 10, error ? 500 : 200, error, slow,
                "trace-test");
    }

    @Test
    @DisplayName("样本不足时判为数据不足，不告警也不算健康")
    void insufficientSamples() {
        AlertRule rule = rule(AlertMetric.ERROR_RATE, 0.5, 10, 60_000);
        List<RequestSample> samples = List.of(
                sample(-100, true, false), sample(-200, true, false));
        AlertVerdict v = AlertVerdict.evaluate(rule, samples, NOW);
        log.info("[数据不足] traceId=trace-test {}", v.reason());
        assertTrue(v.dataInsufficient());
        assertFalse(v.healthy());
    }

    @Test
    @DisplayName("错误率严格大于阈值才异常：等于阈值算健康")
    void thresholdIsStrict() {
        AlertRule rule = rule(AlertMetric.ERROR_RATE, 0.5, 10, 60_000);
        List<RequestSample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            samples.add(sample(-100L - i, i < 5, false)); // 恰好 5/10 = 0.5
        }
        AlertVerdict equal = AlertVerdict.evaluate(rule, samples, NOW);
        log.info("[阈值边界-等于] traceId=trace-test {}", equal.reason());
        assertTrue(equal.healthy());
        assertFalse(equal.dataInsufficient());
        assertEquals(0.5, equal.ratio(), 1e-9);

        samples.add(sample(-500, true, false)); // 6/11 ≈ 0.545 > 0.5
        AlertVerdict above = AlertVerdict.evaluate(rule, samples, NOW);
        log.info("[阈值边界-超过] traceId=trace-test {}", above.reason());
        assertFalse(above.healthy());
    }

    @Test
    @DisplayName("慢请求判定与错误判定互不干扰，复用同一组样本标记")
    void slowMetricUsesSlowFlag() {
        AlertRule slowRule = rule(AlertMetric.SLOW_RATE, 0.5, 4, 60_000);
        List<RequestSample> samples = List.of(
                sample(-10, false, true),
                sample(-20, false, true),
                sample(-30, false, false),
                sample(-40, false, false));
        AlertVerdict v = AlertVerdict.evaluate(slowRule, samples, NOW);
        log.info("[慢请求率] traceId=trace-test {}", v.reason());
        assertTrue(v.healthy()); // 2/4 = 0.5，不严格大于
    }

    @Test
    @DisplayName("窗口外样本不参与判定")
    void windowBoundary() {
        AlertRule rule = rule(AlertMetric.ERROR_RATE, 0.5, 1, 1_000);
        List<RequestSample> samples = List.of(
                sample(-1_500, true, false),  // 窗口外
                sample(-500, false, false)); // 窗口内、健康
        AlertVerdict v = AlertVerdict.evaluate(rule, samples, NOW);
        log.info("[窗口边界] traceId=trace-test {}", v.reason());
        assertEquals(1, v.sampleCount());
        assertTrue(v.healthy());
    }

    @Test
    @DisplayName("同一份样本与规则重复评估结论一致（确定性重放）")
    void replayIsDeterministic() {
        AlertRule rule = rule(AlertMetric.ERROR_RATE, 0.3, 5, 60_000);
        List<RequestSample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(sample(-10L - i, i % 2 == 0, i % 5 == 0));
        }
        AlertVerdict first = AlertVerdict.evaluate(rule, samples, NOW);
        AlertVerdict second = AlertVerdict.evaluate(rule, samples, NOW);
        log.info("[重放一致] traceId=trace-test first={} second={}", first.reason(),
                second.reason());
        assertEquals(first, second);
        // 换评估时刻重放历史：窗口对齐到同一时刻，结论仍一致
        AlertVerdict fixedTime = AlertVerdict.evaluate(rule, samples, NOW);
        assertEquals(first, fixedTime);
    }
}
