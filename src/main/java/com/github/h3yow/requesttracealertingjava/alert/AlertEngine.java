package com.github.h3yow.requesttracealertingjava.alert;

import com.github.h3yow.requesttracealertingjava.config.TraceAlertProperties;
import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import com.github.h3yow.requesttracealertingjava.metrics.RequestSample;
import com.github.h3yow.requesttracealertingjava.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警状态机。
 *
 * <p>每个“规则 + 接口”维度独立维护一份 {@link RuleState}：
 * <ul>
 *   <li>异常必须连续 {@code triggerRounds} 轮成立才 FIRED（数据不足/恢复都会把连续计数清零）；</li>
 *   <li>FIRED 后进入抑制窗口，窗口内即使继续异常也不重复告警，避免告警风暴；</li>
 *   <li>抑制期过后仍异常则重新触发一次；恢复健康则产生 RECOVERED 并回到初始态；</li>
 *   <li>规则更新（version 变化或阈值变化）后状态整体重置，只影响之后的评估；
 *       已经产生的 {@link AlertEvent} 不可变，历史结论永不被改写，因此
 *       “用同一份规则重放同一段历史”结论一致，改配置不会让旧结论前后矛盾。</li>
 * </ul>
 */
@Component
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    /** 事件历史保留上限，防止内存无限增长。 */
    private static final int MAX_EVENTS = 1_000;

    private final MetricsRegistry metricsRegistry;
    private final Clock clock;

    private final Object monitor = new Object();
    private Map<String, RuleRuntime> rules = new HashMap<>();
    private final List<AlertEvent> events = new ArrayList<>();

    public AlertEngine(MetricsRegistry metricsRegistry, TraceAlertProperties properties,
                       Clock clock) {
        this.metricsRegistry = metricsRegistry;
        this.clock = clock;
    }

    /** 规则在引擎中的运行时状态。 */
    private static final class RuleRuntime {
        private final AlertRule rule;
        private RuleState state;

        private RuleRuntime(AlertRule rule) {
            this.rule = rule;
            this.state = new RuleState(false, 0, 0, 0);
        }
    }

    /**
     * @param firing          是否已触发（FIRED）
     * @param consecutiveBad  连续异常评估轮数
     * @param lastFiredAt     最近一次触发时间（用于抑制窗口判定）
     * @param lastEvaluateAt  最近一次评估时间
     */
    private record RuleState(boolean firing, int consecutiveBad, long lastFiredAt,
                             long lastEvaluateAt) {
        private RuleState withFiring(boolean firing, long lastFiredAt) {
            return new RuleState(firing, consecutiveBad, lastFiredAt, lastEvaluateAt);
        }

        private RuleState withConsecutiveBad(int bad) {
            return new RuleState(firing, bad, lastFiredAt, lastEvaluateAt);
        }

        private RuleState withLastEvaluateAt(long t) {
            return new RuleState(firing, consecutiveBad, lastFiredAt, t);
        }
    }

    /**
     * 更新规则集合；仅对之后的评估生效。
     * 规则内容（含版本）完全相同则保留状态，否则该规则状态重置从零累计。
     */
    public void updateRules(List<AlertRule> newRules) {
        synchronized (monitor) {
            Map<String, RuleRuntime> next = new HashMap<>();
            for (AlertRule rule : newRules) {
                RuleRuntime existing = rules.get(rule.id());
                if (existing != null && existing.rule.equals(rule)) {
                    next.put(rule.id(), existing);
                } else {
                    if (existing != null) {
                        log.info("告警规则发生变化，状态重置 ruleId={} oldVersion={} newVersion={}",
                                rule.id(), existing.rule.version(), rule.version());
                    }
                    next.put(rule.id(), new RuleRuntime(rule));
                }
            }
            rules = next;
        }
    }

    /** 用当前系统时钟评估一次。 */
    public void evaluate() {
        evaluate(clock.millis());
    }

    /** 用给定时钟对所有规则做一次评估（纯函数判定 + 状态机推进），测试可注入时间。 */
    public void evaluate(long nowMs) {
        metricsRegistry.evictExpired(nowMs);
        List<RuleRuntime> snapshot;
        synchronized (monitor) {
            snapshot = new ArrayList<>(rules.values());
        }
        for (RuleRuntime runtime : snapshot) {
            evaluateOne(runtime, nowMs);
        }
    }

    private void evaluateOne(RuleRuntime runtime, long nowMs) {
        AlertRule rule = runtime.rule;
        List<RequestSample> samples = metricsRegistry.samplesOf(rule.endpoint(), nowMs);
        AlertVerdict verdict = AlertVerdict.evaluate(rule, samples, nowMs);

        synchronized (monitor) {
            RuleState state = runtime.state.withLastEvaluateAt(nowMs);
            if (verdict.dataInsufficient()) {
                // 数据不足：不触发、不恢复（无法证明健康），连续异常计数清零
                runtime.state = state.withConsecutiveBad(0);
                log.info("告警评估(跳过) ruleId={} endpoint={} metric={} traceId={} {}",
                        rule.id(), rule.endpoint(), rule.metric(), TraceContext.currentTraceId(),
                        verdict.reason());
                return;
            }

            if (!verdict.healthy()) {
                int bad = state.consecutiveBad() + 1;
                runtime.state = state.withConsecutiveBad(bad);
                if (state.firing()) {
                    // 已触发：仅抑制窗口结束后才允许再次触发
                    if (nowMs - state.lastFiredAt() >= rule.suppressionMs()) {
                        fire(runtime, verdict, nowMs);
                    } else {
                        log.info("告警抑制中 ruleId={} endpoint={} 连续异常={} 距上次触发={}ms "
                                        + "< suppressionMs={} traceId={}", rule.id(),
                                rule.endpoint(), bad, nowMs - state.lastFiredAt(),
                                rule.suppressionMs(), TraceContext.currentTraceId());
                    }
                } else if (bad >= rule.triggerRounds()) {
                    fire(runtime, verdict, nowMs);
                } else {
                    log.info("异常未达连续轮次 ruleId={} endpoint={} 连续异常={} < triggerRounds={} "
                                    + "traceId={} {}", rule.id(), rule.endpoint(), bad,
                            rule.triggerRounds(), TraceContext.currentTraceId(), verdict.reason());
                }
                return;
            }

            // 健康：连续异常计数清零；若此前在告警中，则识别为恢复
            runtime.state = state.withConsecutiveBad(0);
            if (state.firing()) {
                recover(runtime, verdict, nowMs);
            } else {
                log.info("状态正常 ruleId={} endpoint={} traceId={} {}", rule.id(),
                        rule.endpoint(), TraceContext.currentTraceId(), verdict.reason());
            }
        }
    }

    private void fire(RuleRuntime runtime, AlertVerdict verdict, long nowMs) {
        AlertRule rule = runtime.rule;
        runtime.state = runtime.state.withFiring(true, nowMs).withConsecutiveBad(0);
        addEvent(new AlertEvent(rule.id(), rule.endpoint(), rule.metric(), true, nowMs,
                verdict.ratio(), verdict.threshold(), verdict.sampleCount(),
                "触发告警: " + verdict.reason(), rule.version()));
        log.warn("【告警触发】ruleId={} endpoint={} metric={} traceId={} {}", rule.id(),
                rule.endpoint(), rule.metric(), TraceContext.currentTraceId(), verdict.reason());
    }

    private void recover(RuleRuntime runtime, AlertVerdict verdict, long nowMs) {
        AlertRule rule = runtime.rule;
        runtime.state = new RuleState(false, 0, runtime.state.lastFiredAt(), nowMs);
        addEvent(new AlertEvent(rule.id(), rule.endpoint(), rule.metric(), false, nowMs,
                verdict.ratio(), verdict.threshold(), verdict.sampleCount(),
                "告警恢复: " + verdict.reason(), rule.version()));
        log.warn("【告警恢复】ruleId={} endpoint={} metric={} traceId={} {}", rule.id(),
                rule.endpoint(), rule.metric(), TraceContext.currentTraceId(), verdict.reason());
    }

    private void addEvent(AlertEvent event) {
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            // 保留最近的事件，维持内存上界
            events.remove(0);
        }
    }

    /** 已产生的告警/恢复事件（按时间顺序的快照，便于测试与排查）。 */
    public List<AlertEvent> events() {
        synchronized (monitor) {
            return List.copyOf(events);
        }
    }
}
