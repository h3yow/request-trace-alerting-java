package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.alert.AlertEngine;
import com.github.h3yow.requesttracealertingjava.alert.AlertEvent;
import com.github.h3yow.requesttracealertingjava.alert.AlertMetric;
import com.github.h3yow.requesttracealertingjava.alert.AlertRule;
import com.github.h3yow.requesttracealertingjava.config.TraceAlertProperties;
import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import com.github.h3yow.requesttracealertingjava.metrics.RequestSample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警状态机测试：触发需连续成立、抑制窗口防风暴、恢复可识别、规则更新只对之后生效。
 * 日志中打印链路标识与判定依据（引擎日志内含 reason）。
 */
class AlertEngineStateTests {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineStateTests.class);

    private static final String ENDPOINT = "GET /api/demo/x";

    private TraceAlertProperties properties;
    private MetricsRegistry registry;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        properties = new TraceAlertProperties();
        properties.setWindowRetentionMs(60_000);
        properties.setMaxSamplesPerEndpoint(1_000);
        properties.setMaxEndpoints(8);
        properties.setSuppressionWindowMs(10_000);
        properties.setTriggerConsecutiveRounds(2);
        registry = new MetricsRegistry(properties);
        engine = new AlertEngine(registry, properties, Clock.systemUTC());
    }

    private AlertRule rule(long version) {
        return new AlertRule("r1", ENDPOINT, AlertMetric.ERROR_RATE, 0.5, 5, 60_000, 2,
                10_000, version);
    }

    /** 记录 n 个样本，其中错误数 errors 个，全部落在 now 之前的窗口内。 */
    private void feed(long now, int total, int errors) {
        for (int i = 0; i < total; i++) {
            boolean error = i < errors;
            registry.record(new RequestSample(ENDPOINT, now - 100 - i, 10, error ? 500 : 200,
                    error, false, "trace-alert-test"));
        }
    }

    @Test
    @DisplayName("异常需连续2轮才触发；触发后进入抑制窗口不重复告警；恢复后产生恢复事件")
    void triggerSuppressRecover() {
        engine.updateRules(List.of(rule(1L)));

        long t = 100_000L;
        // 第1轮：异常（8/10 > 0.5），但只连续1轮，不触发
        feed(t, 10, 8);
        engine.evaluate(t);
        assertTrue(engine.events().isEmpty());
        log.info("traceId=trace-alert-test 第1轮后事件数={}（预期0，需连续2轮）",
                engine.events().size());

        // 第2轮：仍异常 -> 触发
        t += 5_000;
        engine.evaluate(t);
        assertEquals(1, engine.events().size());
        AlertEvent fired = engine.events().get(0);
        assertTrue(fired.firing());
        log.info("traceId=trace-alert-test 第2轮触发: {}", fired.reason());

        // 抑制窗口内继续异常：不重复触发（距上次触发 5s < 10s 抑制窗口）
        t += 5_000;
        engine.evaluate(t);
        assertEquals(1, engine.events().size());
        log.info("traceId=trace-alert-test 抑制窗口内事件数={}（预期1，不重复告警）",
                engine.events().size());

        // 恢复健康（窗口滑过：旧错误样本过期，只留健康样本）
        long recovered = t + 60_000;
        feed(recovered, 10, 0);
        engine.evaluate(recovered);
        assertEquals(2, engine.events().size());
        AlertEvent recover = engine.events().get(1);
        assertFalse(recover.firing());
        log.info("traceId=trace-alert-test 恢复事件: {}", recover.reason());

        // 抑制窗口过后、且恢复时的健康样本也已滑出窗口，再次异常，连续2轮可重新触发
        long badAgain = recovered + 61_000;
        feed(badAgain, 10, 8);
        engine.evaluate(badAgain);
        engine.evaluate(badAgain + 5_000);
        assertEquals(3, engine.events().size());
        assertTrue(engine.events().get(2).firing());
        log.info("traceId=trace-alert-test 抑制结束后再次触发，事件数={}",
                engine.events().size());
    }

    @Test
    @DisplayName("规则更新（版本/内容变化）后状态重置，已产生的历史事件不可变")
    void ruleUpdateResetsStateButKeepsHistory() {
        engine.updateRules(List.of(rule(1L)));
        long t = 200_000L;
        feed(t, 10, 8);
        engine.evaluate(t);
        engine.evaluate(t + 5_000);
        assertEquals(1, engine.events().size());

        // 同内容（含版本）更新：状态保留，再来一轮异常会因抑制不产生事件
        engine.updateRules(List.of(rule(1L)));
        engine.evaluate(t + 6_000);
        assertEquals(1, engine.events().size());

        // 新版本规则：状态重置。只评估1轮异常不应触发（连续计数从零开始）
        AlertRule v2 = new AlertRule("r1", ENDPOINT, AlertMetric.ERROR_RATE, 0.5, 5, 60_000, 2,
                10_000, 2L);
        engine.updateRules(List.of(v2));
        engine.evaluate(t + 7_000);
        assertEquals(1, engine.events().size());
        log.info("traceId=trace-alert-test 规则更新后首轮事件数={}（预期1，状态已重置）",
                engine.events().size());

        // 历史事件仍是 v1 的结论，未被改写
        assertEquals(1L, engine.events().get(0).ruleVersion());
        assertTrue(engine.events().get(0).firing());
    }

    @Test
    @DisplayName("数据不足不会误触发，也不会把已触发状态错误恢复")
    void insufficientDataNeitherFiresNorRecovers() {
        engine.updateRules(List.of(rule(1L)));
        long t = 300_000L;
        // 只喂 2 个样本 < minSamples=5
        feed(t, 2, 2);
        engine.evaluate(t);
        engine.evaluate(t + 5_000);
        assertTrue(engine.events().isEmpty());
        log.info("traceId=trace-alert-test 低样本量事件数={}（预期0，数据不足不告警）",
                engine.events().size());
    }
}
