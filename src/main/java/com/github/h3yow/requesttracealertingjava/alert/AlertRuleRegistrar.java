package com.github.h3yow.requesttracealertingjava.alert;

import com.github.h3yow.requesttracealertingjava.config.TraceAlertProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用启动时把默认规则装载进 {@link AlertEngine}。
 *
 * <p>规则窗口长度、连续轮次、抑制窗口统一取自 {@link TraceAlertProperties}，
 * 保证配置口径一致；规则只影响注册之后的评估窗口。
 */
@Component
public class AlertRuleRegistrar {

    private final AlertEngine engine;
    private final TraceAlertProperties properties;

    public AlertRuleRegistrar(AlertEngine engine, TraceAlertProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @PostConstruct
    void registerDefaults() {
        engine.updateRules(defaultRules(properties, 1L));
    }

    /**
     * 默认规则（演示/本地验证用）：
     * 错误率 > 0.5、慢请求率 > 0.5，均要求窗口内至少 10 个样本、连续 2 轮成立。
     */
    public static List<AlertRule> defaultRules(TraceAlertProperties properties, long version) {
        List<AlertRule> rules = new ArrayList<>();
        rules.add(new AlertRule("demo-error-rate", "GET /api/demo/error", AlertMetric.ERROR_RATE,
                0.5, 10, properties.getWindowRetentionMs(),
                properties.getTriggerConsecutiveRounds(), properties.getSuppressionWindowMs(),
                version));
        rules.add(new AlertRule("demo-slow-rate", "GET /api/demo/slow", AlertMetric.SLOW_RATE,
                0.5, 10, properties.getWindowRetentionMs(),
                properties.getTriggerConsecutiveRounds(), properties.getSuppressionWindowMs(),
                version));
        return rules;
    }
}
