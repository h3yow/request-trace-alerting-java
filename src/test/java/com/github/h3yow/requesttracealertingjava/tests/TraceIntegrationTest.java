package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import com.github.h3yow.requesttracealertingjava.trace.TraceId;
import com.github.h3yow.requesttracealertingjava.trace.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链路标识端到端：生成规则、来源区分、错误响应、内部转发、异步传递。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TraceIntegrationTest.class);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private MetricsRegistry metricsRegistry;

    @Test
    void missingHeaderGeneratesFreshTraceId() {
        ResponseEntity<Map> response = rest.getForEntity("/api/hello", Map.class);
        String traceId = response.getHeaders().getFirst(TraceIdFilter.HEADER);
        String source = response.getHeaders().getFirst(TraceIdFilter.SOURCE_HEADER);
        log.info("missing header case: traceId={} source={} body={}", traceId, source, response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(TraceId.isValid(traceId)).isTrue();
        assertThat(source).isEqualTo("GENERATED_MISSING");
        assertThat(response.getBody()).containsEntry("traceId", traceId);
    }

    @Test
    void validUpstreamTraceIdIsPropagated() {
        String upstream = TraceId.generate();
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceIdFilter.HEADER, upstream);
        ResponseEntity<Map> response = rest.exchange("/api/hello", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        log.info("upstream case: sent={} got={} source={}",
                upstream, response.getHeaders().getFirst(TraceIdFilter.HEADER),
                response.getHeaders().getFirst(TraceIdFilter.SOURCE_HEADER));

        assertThat(response.getHeaders().getFirst(TraceIdFilter.HEADER)).isEqualTo(upstream);
        assertThat(response.getHeaders().getFirst(TraceIdFilter.SOURCE_HEADER)).isEqualTo("UPSTREAM");
        assertThat(response.getBody()).containsEntry("traceId", upstream);
    }

    @Test
    void invalidUpstreamTraceIdIsReplacedNotPropagated() {
        String dirty = "<script>alert(1)</script>";
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceIdFilter.HEADER, dirty);
        ResponseEntity<Map> response = rest.exchange("/api/hello", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        String traceId = response.getHeaders().getFirst(TraceIdFilter.HEADER);
        String source = response.getHeaders().getFirst(TraceIdFilter.SOURCE_HEADER);
        log.info("invalid header case: dirty={} replacedBy={} source={}", dirty, traceId, source);

        assertThat(TraceId.isValid(traceId)).isTrue();
        assertThat(traceId).isNotEqualTo(dirty);
        assertThat(source).isEqualTo("GENERATED_INVALID");
        assertThat(response.getBody()).containsEntry("traceId", traceId);
    }

    @Test
    void errorResponseCarriesSameTraceId() {
        ResponseEntity<Map> response = rest.getForEntity("/api/fail", Map.class);
        String traceId = response.getHeaders().getFirst(TraceIdFilter.HEADER);
        log.info("error case: status={} traceId={} body={}", response.getStatusCode(), traceId, response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(TraceId.isValid(traceId)).isTrue();
        assertThat(response.getBody()).containsEntry("traceId", traceId);
    }

    @Test
    void forwardKeepsTraceIdAndCountsOnce() {
        long helloBefore = countOf("GET /api/hello");
        long forwardBefore = countOf("GET /api/forward");

        ResponseEntity<String> response = rest.getForEntity("/api/forward", String.class);
        String traceId = response.getHeaders().getFirst(TraceIdFilter.HEADER);
        log.info("forward case: traceId={} body={}", traceId, response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 转发后的 /api/hello 响应体里的标识与入口响应头一致
        assertThat(response.getBody()).contains(traceId);

        long helloAfter = countOf("GET /api/hello");
        long forwardAfter = countOf("GET /api/forward");
        log.info("forward counting: hello {} -> {}, forward {} -> {}",
                helloBefore, helloAfter, forwardBefore, forwardAfter);
        // 整次请求只在入口计一次，内部转发不重复计数
        assertThat(forwardAfter - forwardBefore).isEqualTo(1);
        assertThat(helloAfter - helloBefore).isZero();
    }

    @Test
    void asyncBoundaryKeepsTraceId() {
        ResponseEntity<Map> response = rest.getForEntity("/api/async", Map.class);
        String traceId = response.getHeaders().getFirst(TraceIdFilter.HEADER);
        log.info("async case: headerTraceId={} body={}", traceId, response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("callerTraceId", traceId)
                .containsEntry("asyncTraceId", traceId);
    }

    private long countOf(String endpoint) {
        var metrics = metricsRegistry.get(endpoint);
        return metrics == null ? 0 : metrics.getTotalCount();
    }
}
