package com.github.h3yow.requesttracealertingjava.alert;

/**
 * 告警事件：触发 / 恢复。
 */
public record AlertEvent(
        Type type,
        String endpoint,
        String reason,
        SlidingWindow.WindowStats stats,
        long timestampMillis) {

    public enum Type {
        TRIGGERED,
        RECOVERED
    }
}
