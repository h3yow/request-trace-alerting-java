package com.github.h3yow.requesttracealertingjava.web;

import com.github.h3yow.requesttracealertingjava.trace.AsyncConfig;
import com.github.h3yow.requesttracealertingjava.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 演示接口：正常 / 异常 / 慢请求 / 异步 / 内部转发，
 * 用于验证链路标识传递与指标、告警行为。
 */
@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final AsyncWorker asyncWorker;

    public DemoController(AsyncWorker asyncWorker) {
        this.asyncWorker = asyncWorker;
    }

    /** 正常请求：返回当前链路标识。 */
    @GetMapping("/api/hello")
    public Map<String, String> hello() {
        log.info("handling /api/hello");
        return Map.of("message", "hello", "traceId", TraceContext.getTraceId());
    }

    /** 异常请求：抛出运行时异常，由全局异常处理器接管。 */
    @GetMapping("/api/fail")
    public Map<String, String> fail() {
        throw new IllegalStateException("simulated failure");
    }

    /** 慢请求：按参数休眠，用于触发慢请求判定。 */
    @GetMapping("/api/slow")
    public Map<String, String> slow(@RequestParam(defaultValue = "1500") long sleepMillis) throws InterruptedException {
        Thread.sleep(sleepMillis);
        return Map.of("message", "slow", "traceId", TraceContext.getTraceId());
    }

    /** 异步请求：在异步线程中读取链路标识，验证跨线程传递。 */
    @GetMapping("/api/async")
    public CompletableFuture<Map<String, String>> async() {
        String callerTraceId = TraceContext.getTraceId();
        return asyncWorker.work()
                .thenApply(asyncTraceId -> Map.of(
                        "callerTraceId", callerTraceId,
                        "asyncTraceId", asyncTraceId));
    }

    /** 内部转发：转发到 /api/hello，链路标识应保持一致且指标只计一次。 */
    @GetMapping("/api/forward")
    public ModelAndView forward() {
        return new ModelAndView("forward:/api/hello");
    }

    @Component
    public static class AsyncWorker {

        @Async(AsyncConfig.EXECUTOR)
        public CompletableFuture<String> work() {
            return CompletableFuture.completedFuture(TraceContext.getTraceId());
        }
    }
}
