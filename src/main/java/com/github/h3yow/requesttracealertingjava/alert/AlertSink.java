package com.github.h3yow.requesttracealertingjava.alert;

/**
 * 告警输出口。
 */
public interface AlertSink {

    void emit(AlertEvent event);
}
