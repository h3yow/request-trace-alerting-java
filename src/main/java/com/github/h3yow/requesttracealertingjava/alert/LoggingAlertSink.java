package com.github.h3yow.requesttracealertingjava.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志告警输出：触发/恢复都打印结构化日志，带判定依据。
 */
public class LoggingAlertSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingAlertSink.class);

    @Override
    public void emit(AlertEvent event) {
        if (event.type() == AlertEvent.Type.TRIGGERED) {
            log.warn("ALERT TRIGGERED endpoint={} reason={} stats={}", event.endpoint(), event.reason(), event.stats());
        } else {
            log.info("ALERT RECOVERED endpoint={} reason={} stats={}", event.endpoint(), event.reason(), event.stats());
        }
    }
}
