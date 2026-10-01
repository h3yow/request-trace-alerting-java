package com.github.h3yow.requesttracealertingjava.metrics;

/**
 * 某接口在当前滑动窗口内的聚合统计（不可变快照）。
 *
 * @param endpoint             接口维度键
 * @param requestCount         当前窗口内请求数
 * @param lifetimeRequestCount 进程生命周期内累计请求数（只增，供总量核对）
 * @param errorCount           当前窗口内错误请求数（HTTP >= 500 或处理抛异常）
 * @param slowCount            当前窗口内慢请求数（耗时 >= 慢请求阈值）
 * @param totalDurationMs      当前窗口内总耗时
 * @param maxDurationMs        当前窗口内最大耗时
 * @param errorRate           错误率 = errorCount / requestCount（无样本为 0）
 * @param avgDurationMs        平均耗时 = totalDurationMs / requestCount
 * @param windowStartMs        窗口实际起点（最早样本时间）
 * @param windowEndMs          评估时刻
 */
public record EndpointStats(String endpoint, long requestCount, long lifetimeRequestCount,
                            long errorCount, long slowCount, long totalDurationMs,
                            long maxDurationMs, double errorRate, double avgDurationMs,
                            long windowStartMs, long windowEndMs) {
}
