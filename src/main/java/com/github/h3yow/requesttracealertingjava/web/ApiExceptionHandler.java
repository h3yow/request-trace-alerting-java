package com.github.h3yow.requesttracealertingjava.web;

import com.github.h3yow.requesttracealertingjava.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 控制器抛出异常时的统一处理：日志与错误体均携带当前链路标识。
 *
 * <p>覆盖两类：
 * <ul>
 *   <li>框架内建的 4xx 异常（如 Spring Boot 3.x 未命中路径抛
 *       {@link NoResourceFoundException}），必须保持 404 语义，不能被兜底成 500；</li>
 *   <li>其余未预期异常统一 500。过滤器层逃逸的异常由 TraceFilter 兜底，口径一致。</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 未命中任何处理器/静态资源：404（Spring Boot 3.2+ 的默认行为是抛此异常）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException ex,
                                                           HttpServletRequest request) {
        String traceId = TraceContext.currentTraceId();
        log.warn("资源不存在 traceId={} method={} uri={}", traceId, request.getMethod(),
                request.getRequestURI());
        // 告知指标过滤器：此请求未匹配业务接口，归固定 UNMAPPED 桶
        request.setAttribute(
                com.github.h3yow.requesttracealertingjava.metrics.MetricsFilter.UNMATCHED_ATTRIBUTE,
                Boolean.TRUE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(404, "Not Found", "请求的接口不存在", traceId));
    }

    /** 方法不支持：405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String traceId = TraceContext.currentTraceId();
        log.warn("方法不支持 traceId={} method={} uri={}", traceId, request.getMethod(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiErrorResponse(405, "Method Not Allowed",
                        "不支持的请求方法: " + ex.getMethod(), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleAny(Exception ex, HttpServletRequest request) {
        String traceId = TraceContext.currentTraceId();
        log.error("接口处理异常 traceId={} method={} uri={} reason={}", traceId,
                request.getMethod(), request.getRequestURI(), ex.toString(), ex);
        ApiErrorResponse body = new ApiErrorResponse(500, "Internal Server Error",
                "请求处理失败，请凭链路标识联系排查: " + traceId, traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
