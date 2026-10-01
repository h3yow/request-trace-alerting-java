package com.github.h3yow.requesttracealertingjava.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 入口过滤器：解析/生成链路标识，写入 {@link TraceContext} 与日志 MDC，并回写响应头。
 *
 * <p>处理规则：
 * <ul>
 *   <li>上游未携带：生成新标识，来源记为 GENERATED_MISSING；</li>
 *   <li>上游携带但格式非法：生成新标识，来源记为 GENERATED_INVALID，记录警告日志，
 *       非法值只出现在日志中，不会透传到响应头或下游；</li>
 *   <li>上游携带且合法：沿用该标识，来源记为 UPSTREAM。</li>
 * </ul>
 *
 * <p>继承 {@link OncePerRequestFilter}，内部转发（FORWARD/ERROR 分发）不会重复处理，
 * 保证同一次请求链路标识一致。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    public static final String HEADER = "X-Trace-Id";
    public static final String SOURCE_HEADER = "X-Trace-Id-Source";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId;
        TraceContext.Source source;
        if (incoming == null || incoming.isBlank()) {
            traceId = TraceId.generate();
            source = TraceContext.Source.GENERATED_MISSING;
        } else if (TraceId.isValid(incoming)) {
            traceId = incoming;
            source = TraceContext.Source.UPSTREAM;
        } else {
            traceId = TraceId.generate();
            source = TraceContext.Source.GENERATED_INVALID;
            log.warn("invalid trace id from upstream, generated a new one; uri={}", request.getRequestURI());
        }

        TraceContext.set(traceId, source);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        response.setHeader(SOURCE_HEADER, source.name());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
