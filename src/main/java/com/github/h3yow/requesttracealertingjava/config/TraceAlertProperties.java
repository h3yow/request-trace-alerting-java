package com.github.h3yow.requesttracealertingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 链路/指标/告警相关的可配置项。
 */
@ConfigurationProperties(prefix = "observability")
public class TraceAlertProperties {

    /** 慢请求阈值（毫秒），同时用于指标打点与告警判定。 */
    private long slowRequestThresholdMs = 500;

    /** 指标滑动窗口保留时长（毫秒）。 */
    private long windowRetentionMs = 60_000;

    /** 每个接口在窗口内最多保留的样本数（内存上界）。 */
    private int maxSamplesPerEndpoint = 10_000;

    /** 告警评估周期（毫秒）。 */
    private long alertEvaluationIntervalMs = 5_000;

    /** 触发告警要求异常状态连续成立的评估次数。 */
    private int triggerConsecutiveRounds = 2;

    /** 触发后抑制窗口（毫秒），期间不重复告警。 */
    private int suppressionWindowMs = 60_000;

    /** 指标键（接口）数量上限，防止无限增长。 */
    private int maxEndpoints = 256;

    public long getSlowRequestThresholdMs() {
        return slowRequestThresholdMs;
    }

    public void setSlowRequestThresholdMs(long slowRequestThresholdMs) {
        this.slowRequestThresholdMs = slowRequestThresholdMs;
    }

    public long getWindowRetentionMs() {
        return windowRetentionMs;
    }

    public void setWindowRetentionMs(long windowRetentionMs) {
        this.windowRetentionMs = windowRetentionMs;
    }

    public int getMaxSamplesPerEndpoint() {
        return maxSamplesPerEndpoint;
    }

    public void setMaxSamplesPerEndpoint(int maxSamplesPerEndpoint) {
        this.maxSamplesPerEndpoint = maxSamplesPerEndpoint;
    }

    public long getAlertEvaluationIntervalMs() {
        return alertEvaluationIntervalMs;
    }

    public void setAlertEvaluationIntervalMs(long alertEvaluationIntervalMs) {
        this.alertEvaluationIntervalMs = alertEvaluationIntervalMs;
    }

    public int getTriggerConsecutiveRounds() {
        return triggerConsecutiveRounds;
    }

    public void setTriggerConsecutiveRounds(int triggerConsecutiveRounds) {
        this.triggerConsecutiveRounds = triggerConsecutiveRounds;
    }

    public int getSuppressionWindowMs() {
        return suppressionWindowMs;
    }

    public void setSuppressionWindowMs(int suppressionWindowMs) {
        this.suppressionWindowMs = suppressionWindowMs;
    }

    public int getMaxEndpoints() {
        return maxEndpoints;
    }

    public void setMaxEndpoints(int maxEndpoints) {
        this.maxEndpoints = maxEndpoints;
    }
}
