package com.github.h3yow.requesttracealertingjava.alert;

/**
 * 不可变告警事件（触发或恢复），作为历史结论长期可查。
 */
public record AlertEvent(String ruleId, String endpoint, AlertMetric metric, boolean firing,
                         long timestampMs, double ratio, double threshold, long sampleCount,
                         String reason, long ruleVersion) {
}
