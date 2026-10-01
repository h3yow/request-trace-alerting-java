package com.github.h3yow.requesttracealertingjava.web;

import com.github.h3yow.requesttracealertingjava.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：错误响应体里带上链路标识，方便按标识串起整次请求。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex) {
        String traceId = TraceContext.getTraceId();
        log.error("request failed: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getClass().getSimpleName());
        body.put("message", ex.getMessage());
        body.put("traceId", traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
