package com.github.h3yow.requesttracealertingjava.tests;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证请求处理期间打出的日志行均通过 MDC 携带链路标识，
 * 且日志内容包含判定依据（指标记录日志）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceLogMdcIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger targetLogger;

    @AfterEach
    void detach() {
        if (targetLogger != null) {
            targetLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("同步请求：com.github 包日志的 MDC 均带 traceId；异步请求的工作线程日志也带")
    void logsContainTraceId() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger("com.github.h3yow");
        appender.start();
        root.addAppender(appender);
        targetLogger = root;

        String traceId = "a1b2c3d4e5f600112233445566778899";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", traceId);

        restTemplate.exchange("http://localhost:" + port + "/api/demo/ok", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        restTemplate.exchange("http://localhost:" + port + "/api/demo/async", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class).getBody();
        // 等异步线程日志落盘到 appender
        Thread.sleep(200);

        List<ILoggingEvent> events = appender.list;
        boolean sawControllerLog = false;
        boolean sawAsyncWorkerLog = false;
        boolean sawMetricsBasisLog = false;
        for (ILoggingEvent event : events) {
            String mdcTraceId = event.getMDCPropertyMap().get("traceId");
            String formatted = event.getFormattedMessage();
            if (formatted.contains("处理正常请求") || formatted.contains("异步线程执行业务")) {
                org.junit.jupiter.api.Assertions.assertEquals(traceId, mdcTraceId,
                        "业务日志 MDC 必须带入口 traceId: " + formatted);
                sawControllerLog = true;
            }
            if (formatted.contains("异步线程执行业务")) {
                sawAsyncWorkerLog = true;
                // 必须来自异步工作线程，证明跨线程 MDC 传递
                assertTrue(event.getThreadName().startsWith("trace-async-"));
            }
            if (formatted.contains("判定依据")) {
                sawMetricsBasisLog = true;
                org.junit.jupiter.api.Assertions.assertEquals(traceId, mdcTraceId);
                assertTrue(formatted.contains("error=") && formatted.contains("slow="));
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(sawControllerLog, "应捕获到控制器日志");
        org.junit.jupiter.api.Assertions.assertTrue(sawAsyncWorkerLog, "应捕获到异步工作线程日志");
        org.junit.jupiter.api.Assertions.assertTrue(sawMetricsBasisLog,
                "应捕获到含判定依据的指标日志");
    }
}
