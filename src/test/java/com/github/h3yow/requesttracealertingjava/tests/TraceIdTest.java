package com.github.h3yow.requesttracealertingjava.tests;

import com.github.h3yow.requesttracealertingjava.trace.TraceId;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链路标识的生成与校验规则。
 */
class TraceIdTest {

    private static final Logger log = LoggerFactory.getLogger(TraceIdTest.class);

    @Test
    void generatedIdsAreValidAndUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = TraceId.generate();
            assertThat(TraceId.isValid(id)).isTrue();
            ids.add(id);
        }
        assertThat(ids).hasSize(10_000);
        log.info("generated 10000 unique valid trace ids, sample={}", ids.iterator().next());
    }

    @Test
    void validationRejectsIllegalValues() {
        assertThat(TraceId.isValid(null)).isFalse();
        assertThat(TraceId.isValid("")).isFalse();
        assertThat(TraceId.isValid("abc")).isFalse();                                    // 太短
        assertThat(TraceId.isValid("0123456789abcdef0123456789abcde")).isFalse();        // 31 位
        assertThat(TraceId.isValid("0123456789abcdef0123456789abcdef0")).isFalse();      // 33 位
        assertThat(TraceId.isValid("0123456789ABCDEF0123456789ABCDEF")).isFalse();       // 大写
        assertThat(TraceId.isValid("0123456789abcdef0123456789abcdeg")).isFalse();       // 非十六进制
        assertThat(TraceId.isValid("<script>alert(1)</script>00000000")).isFalse();      // 注入脏值
        assertThat(TraceId.isValid("0123456789abcdef0123456789abcdef")).isTrue();
        log.info("validation rules verified: only 32 lowercase hex chars accepted");
    }
}
