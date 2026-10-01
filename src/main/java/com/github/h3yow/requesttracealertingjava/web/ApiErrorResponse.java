package com.github.h3yow.requesttracealertingjava.web;

/**
 * 统一错误响应体。
 */
public record ApiErrorResponse(int status, String error, String message, String traceId) {
}
