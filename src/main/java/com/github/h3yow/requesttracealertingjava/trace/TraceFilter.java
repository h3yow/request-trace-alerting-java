package com.github.h3yow.requesttracealertingjava.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.requesttracealertingjava.web.ApiErrorResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 每个请求入口解析/生成链路标识，绑定到上下文与 MDC，
 * 出错时给出统一响应，并在响应头回写标识。
 *
 * <p>处理规则：
 * <ul>
 *   <li>头缺失或空白：生成新标识，响应头 {@code X-Trace-Id-Source=generated}；</li>
 *   <li>头值合法：沿用，{@code X-Trace-Id-Source=propagated}；</li>
 *   <li>头值非法：记录原因（告警日志）、丢弃脏值并生成新值，
 *       {@code X-Trace-Id-Source=rejected}；对外只暴露干净的新值；</li>
 *   <li>ERROR 分发（如 404 转发到 /error）时从请求属性恢复标识与 MDC；</li>
 *   <li>逃逸到过滤器层的异常统一回 500 JSON，错误体携带 traceId。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    public static final String SOURCE_HEADER = "X-Trace-Id-Source";
    public static final String ATTR_TRACE_ID = TraceFilter.class.getName() + ".traceId";
    static final String ATTR_RESOLUTION = TraceFilter.class.getName() + ".resolution";

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** ERROR 分发也要经过本过滤器，以便恢复 MDC 并补写响应头。 */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** ASYNC 分发同样经过，以便在容器异步线程上恢复 MDC。 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (DispatcherType.ERROR.equals(request.getDispatcherType())
                || DispatcherType.ASYNC.equals(request.getDispatcherType())) {
            // ERROR：转发 /error；ASYNC：容器异步完成后重新分发。
            // 二者都在新的容器线程上，需要从请求属性恢复 MDC，保证该阶段日志仍带标识。
            restoreForErrorDispatch(request, response);
            chain.doFilter(request, response);
            return;
        }

        String raw = request.getHeader(TraceIds.HEADER_NAME);
        String traceId;
        TraceIdResolution resolution;
        if (raw == null || raw.isBlank()) {
            traceId = TraceIds.generate();
            resolution = TraceIdResolution.GENERATED;
        } else if (TraceIds.isValid(raw.trim())) {
            traceId = raw.trim();
            resolution = TraceIdResolution.PROPAGATED;
        } else {
            // 明确记录拒绝原因，不静默丢弃；脏值做日志安全清洗，且绝不写入响应
            log.warn("收到非法链路标识，已拒绝并重新生成 reason=invalid_format rawLength={} rawValue={}",
                    raw.length(), sanitizeForLog(raw));
            traceId = TraceIds.generate();
            resolution = TraceIdResolution.REJECTED;
        }

        TraceContext.bind(traceId, resolution);
        request.setAttribute(ATTR_TRACE_ID, traceId);
        request.setAttribute(ATTR_RESOLUTION, resolution.name());
        writeTraceHeaders(response, traceId, resolution);

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } catch (Exception ex) {
            handleEscapedException(request, response, traceId, ex);
        } finally {
            log.debug("请求处理结束 traceId={} resolution={} method={} uri={} status={} durationMs={}",
                    traceId, resolution, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), System.currentTimeMillis() - start);
            TraceContext.clear();
        }
    }

    private void restoreForErrorDispatch(HttpServletRequest request, HttpServletResponse response) {
        Object idAttr = request.getAttribute(ATTR_TRACE_ID);
        Object resAttr = request.getAttribute(ATTR_RESOLUTION);
        if (idAttr instanceof String traceId) {
            TraceIdResolution resolution = resAttr instanceof String s
                    ? TraceIdResolution.valueOf(s) : TraceIdResolution.GENERATED;
            TraceContext.bind(traceId, resolution);
            writeTraceHeaders(response, traceId, resolution);
        } else {
            // 极端情况下没有初始请求的属性（直接打到 /error），补生成一个
            String traceId = TraceIds.generate();
            TraceContext.bind(traceId, TraceIdResolution.GENERATED);
            writeTraceHeaders(response, traceId, TraceIdResolution.GENERATED);
        }
    }

    private void writeTraceHeaders(HttpServletResponse response, String traceId,
                                   TraceIdResolution resolution) {
        response.setHeader(TraceIds.HEADER_NAME, traceId);
        response.setHeader(SOURCE_HEADER, resolution.name().toLowerCase());
    }

    private void handleEscapedException(HttpServletRequest request, HttpServletResponse response,
                                        String traceId, Exception ex) throws IOException {
        log.error("请求处理异常 traceId={} method={} uri={}", traceId, request.getMethod(),
                request.getRequestURI(), ex);
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiErrorResponse body = new ApiErrorResponse(500, "Internal Server Error",
                "请求处理失败，请凭链路标识联系排查: " + traceId, traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }

    /** 去掉控制字符并截断，防止日志伪造和日志膨胀。 */
    static String sanitizeForLog(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "?");
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64) + "...";
    }
}
