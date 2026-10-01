package com.github.h3yow.requesttracealertingjava.demo;

import com.github.h3yow.requesttracealertingjava.config.TraceAlertProperties;
import com.github.h3yow.requesttracealertingjava.trace.TraceContext;
import com.github.h3yow.requesttracealertingjava.trace.TraceIdResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 用于本地与集成测试验证的演示接口：
 * 正常、异常、慢请求、异步链路传递、服务端内部转发。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final Executor taskExecutor;
    private final long slowThresholdMs;

    public DemoController(@Qualifier("traceTaskExecutor") Executor taskExecutor,
                          TraceAlertProperties properties) {
        this.taskExecutor = taskExecutor;
        this.slowThresholdMs = properties.getSlowRequestThresholdMs();
    }

    @GetMapping("/ok")
    public Map<String, Object> ok() {
        log.info("处理正常请求 traceId={}", TraceContext.currentTraceId());
        return payload("ok");
    }

    @GetMapping("/error")
    public Map<String, Object> error() {
        log.warn("即将抛出演示异常 traceId={}", TraceContext.currentTraceId());
        throw new IllegalStateException("演示用业务异常");
    }

    @GetMapping("/slow")
    public Map<String, Object> slow() throws InterruptedException {
        long sleepMs = slowThresholdMs + 100;
        log.info("处理慢请求 traceId={} sleepMs={} thresholdMs={}", TraceContext.currentTraceId(),
                sleepMs, slowThresholdMs);
        Thread.sleep(sleepMs);
        return payload("slow-" + sleepMs + "ms");
    }

    /**
     * 异步接口：业务逻辑在线程池执行。若装饰器生效，子线程能读到与入口完全相同的标识。
     */
    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async() {
        String entryTraceId = TraceContext.currentTraceId();
        TraceIdResolution entryResolution = TraceContext.currentResolution();
        log.info("提交异步任务 traceId={}", entryTraceId);
        return CompletableFuture.supplyAsync(() -> {
            String workerTraceId = TraceContext.currentTraceId();
            TraceIdResolution workerResolution = TraceContext.currentResolution();
            log.info("异步线程执行业务 traceId={} entryTraceId={} 一致={}", workerTraceId,
                    entryTraceId, entryTraceId.equals(workerTraceId));
            if (!entryTraceId.equals(workerTraceId) || entryResolution != workerResolution) {
                throw new IllegalStateException("链路标识未正确传递到异步线程");
            }
            Map<String, Object> body = payload("async");
            body.put("workerTraceId", workerTraceId);
            body.put("thread", Thread.currentThread().getName());
            return body;
        }, taskExecutor);
    }

    /** 服务端内部转发：整次请求（两条控制器映射）必须共享同一个链路标识。 */
    @GetMapping("/forward-source")
    public void forwardSource(jakarta.servlet.http.HttpServletRequest request,
                              jakarta.servlet.http.HttpServletResponse response)
            throws jakarta.servlet.ServletException, java.io.IOException {
        log.info("forward 源处理 traceId={}", TraceContext.currentTraceId());
        request.getRequestDispatcher("/api/demo/forward-target").forward(request, response);
    }

    @GetMapping("/forward-target")
    public Map<String, Object> forwardTarget() {
        log.info("forward 目标处理 traceId={}", TraceContext.currentTraceId());
        return payload("forward-target");
    }

    private Map<String, Object> payload(String result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", result);
        body.put("traceId", TraceContext.currentTraceId());
        body.put("resolution", String.valueOf(TraceContext.currentResolution()));
        return body;
    }
}
