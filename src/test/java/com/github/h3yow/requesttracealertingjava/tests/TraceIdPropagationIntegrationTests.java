package com.github.h3yow.requesttracealertingjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路标识的端到端集成测试：
 * 缺失生成、合法透传、非法拒绝（不透脏值）、错误响应携带、异步/转发一致性。
 * 测试日志统一打印本次请求的链路标识，便于在 surefire 输出中串联。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceIdPropagationIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(TraceIdPropagationIntegrationTests.class);
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[0-9a-f]{8,64}");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("未携带标识时生成合法值，响应头声明来源 generated，日志 MDC 可用")
    void generatesWhenMissing() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/demo/ok"), Map.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        String source = resp.getHeaders().getFirst("X-Trace-Id-Source");
        log.info("traceId={} 缺失头场景 status={} source={}", traceId, resp.getStatusCode(), source);
        assertNotNull(traceId);
        assertTrue(VALID_TRACE_ID.matcher(traceId).matches());
        assertEquals("generated", source);
        assertEquals(traceId, resp.getBody().get("traceId"));
    }

    @Test
    @DisplayName("携带合法标识时原样沿用")
    void propagatesValid() {
        String incoming = "0123456789abcdef0123456789abcdef";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", incoming);
        ResponseEntity<Map> resp = restTemplate.exchange(url("/api/demo/ok"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        log.info("traceId={} 合法透传场景 source={}", traceId,
                resp.getHeaders().getFirst("X-Trace-Id-Source"));
        assertEquals(incoming, traceId);
        assertEquals("propagated", resp.getHeaders().getFirst("X-Trace-Id-Source"));
    }

    @Test
    @DisplayName("非法标识被拒绝并重新生成，脏值不会出现在响应中")
    void rejectsInvalid() {
        String dirty = "BAD-VALUE-not-hex-ZZ";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", dirty);
        ResponseEntity<Map> resp = restTemplate.exchange(url("/api/demo/ok"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        log.info("traceId={} 非法值拒绝场景（脏值不落响应）", traceId);
        assertNotNull(traceId);
        assertTrue(VALID_TRACE_ID.matcher(traceId).matches());
        assertFalse(traceId.contains("BAD"));
        assertEquals("rejected", resp.getHeaders().getFirst("X-Trace-Id-Source"));
    }

    @Test
    @DisplayName("异常接口返回 500 统一错误体，其中携带链路标识")
    void errorResponseCarriesTraceId() {
        String incoming = "abcdef0123456789abcdef0123456789";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", incoming);
        ResponseEntity<Map> resp = restTemplate.exchange(url("/api/demo/error"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        log.info("traceId={} 异常响应场景 status={} body={}", incoming, resp.getStatusCode(),
                resp.getBody());
        assertEquals(500, resp.getStatusCode().value());
        assertEquals(incoming, resp.getHeaders().getFirst("X-Trace-Id"));
        assertEquals(incoming, resp.getBody().get("traceId"));
    }

    @Test
    @DisplayName("不存在的路径返回 404，错误页同样携带链路标识")
    void notFoundCarriesTraceId() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/api/demo/does-not-exist"),
                Map.class);
        String traceId = resp.getHeaders().getFirst("X-Trace-Id");
        log.info("traceId={} 404场景 status={}", traceId, resp.getStatusCode());
        assertEquals(404, resp.getStatusCode().value());
        assertNotNull(traceId);
        assertTrue(VALID_TRACE_ID.matcher(traceId).matches());
        assertEquals(traceId, resp.getBody().get("traceId"));
    }

    @Test
    @DisplayName("异步任务在工作线程上读到与入口一致的标识")
    @SuppressWarnings("unchecked")
    void asyncPropagation() {
        String incoming = "fedcba9876543210fedcba9876543210";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", incoming);
        ResponseEntity<Map> resp = restTemplate.exchange(url("/api/demo/async"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = resp.getBody();
        log.info("traceId={} 异步场景 workerTraceId={} thread={}", incoming,
                body == null ? null : body.get("workerTraceId"),
                body == null ? null : body.get("thread"));
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(body);
        assertEquals(incoming, body.get("workerTraceId"));
        assertTrue(String.valueOf(body.get("thread")).startsWith("trace-async-"));
    }

    @Test
    @DisplayName("服务端内部转发：源与目标共享同一个链路标识")
    void forwardKeepsTraceId() {
        String incoming = "11223344556677881122334455667788";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", incoming);
        ResponseEntity<Map> resp = restTemplate.exchange(url("/api/demo/forward-source"),
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        log.info("traceId={} 内部转发场景 status={} body={}", incoming, resp.getStatusCode(),
                resp.getBody());
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("forward-target", resp.getBody().get("result"));
        assertEquals(incoming, resp.getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    @DisplayName("边界：空白头值按缺失处理而非非法拒绝")
    void blankHeaderTreatedAsMissing() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "   ");
        ResponseEntity<Map> resp = restTemplate.exchange(url("/api/demo/ok"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        String source = resp.getHeaders().getFirst("X-Trace-Id-Source");
        log.info("traceId={} 空白头场景 source={}", resp.getHeaders().getFirst("X-Trace-Id"),
                source);
        assertEquals("generated", source);
    }
}
