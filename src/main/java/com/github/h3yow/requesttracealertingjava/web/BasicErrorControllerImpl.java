package com.github.h3yow.requesttracealertingjava.web;

import com.github.h3yow.requesttracealertingjava.trace.TraceContext;
import com.github.h3yow.requesttracealertingjava.trace.TraceFilter;
import com.github.h3yow.requesttracealertingjava.trace.TraceIds;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接管容器错误页（如 404），使未进入控制器的错误响应也携带链路标识。
 *
 * <p>走到这里的请求已经过 TraceFilter 的 ERROR 分发恢复了 MDC，
 * 因此日志中同样能看到 traceId。
 */
@RestController
public class BasicErrorControllerImpl implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(BasicErrorControllerImpl.class);

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ApiErrorResponse> error(HttpServletRequest request) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttr instanceof Integer code ? code : 500;
        HttpStatus httpStatus = resolveStatus(status);

        String traceId = TraceContext.currentTraceId();
        if (traceId == null) {
            Object attr = request.getAttribute(TraceFilter.ATTR_TRACE_ID);
            traceId = attr instanceof String id ? id : TraceIds.generate();
        }

        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        log.warn("错误页响应 traceId={} status={} path={}", traceId, status, path);

        String message = switch (httpStatus) {
            case NOT_FOUND -> "请求的接口不存在";
            case INTERNAL_SERVER_ERROR -> "服务内部错误，请凭链路标识联系排查: " + traceId;
            default -> httpStatus.getReasonPhrase();
        };
        return ResponseEntity.status(httpStatus)
                .body(new ApiErrorResponse(httpStatus.value(), httpStatus.getReasonPhrase(),
                        message, traceId));
    }

    private static HttpStatus resolveStatus(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
    }
}
