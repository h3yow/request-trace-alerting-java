package com.github.h3yow.requesttracealertingjava.alert;

/**
 * 告警判定的指标类型，与指标统计口径保持一致：
 * ERROR_RATE  -> 窗口内错误请求占比（错误定义：HTTP 状态码 >= 500 或处理抛异常）
 * SLOW_RATE   -> 窗口内慢请求占比（慢定义：耗时 >= observability.slow-request-threshold-ms）
 */
public enum AlertMetric {
    ERROR_RATE,
    SLOW_RATE
}
