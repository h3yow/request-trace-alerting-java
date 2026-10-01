package com.github.h3yow.requesttracealertingjava.alert;

/**
 * 告警规则（不可变）：滑动窗口阈值判定的全部参数。
 * 规则更新只影响更新之后的新窗口，历史窗口的重评估仍用旧规则。
 */
public record AlertRule(
        long windowSizeMillis,
        long minRequests,
        double maxErrorRate,
        long slowThresholdMillis,
        long maxSlowCount,
        int consecutiveWindows,
        long suppressionMillis) {

    public static AlertRule defaults() {
        return new AlertRule(60_000, 10, 0.5, 1_000, 5, 2, 300_000);
    }
}
