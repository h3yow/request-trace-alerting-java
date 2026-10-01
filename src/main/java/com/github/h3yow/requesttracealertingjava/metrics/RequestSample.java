package com.github.h3yow.requesttracealertingjava.metrics;

/**
 * 一次请求处理完成后的不可变样本。
 *
 * @param endpoint   接口维度键，例如 "GET /api/demo/ok"
 * @param startMs    请求开始时间（epoch 毫秒）
 * @param durationMs 处理耗时（毫秒）
 * @param status     HTTP 响应状态码
 * @param error      是否错误请求（状态码 >= 500，或处理过程中抛出异常）
 * @param slow       是否慢请求（耗时 >= 配置阈值）
 * @param traceId    链路标识，仅用于日志排查，不参与判定
 */
public record RequestSample(String endpoint, long startMs, long durationMs, int status,
                            boolean error, boolean slow, String traceId) {
}
