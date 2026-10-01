package com.github.h3yow.requesttracealertingjava.trace;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路标识的生成与校验工具。
 *
 * <p>判定口径（详见 README）：
 * <ul>
 *   <li>合法字符：小写十六进制 {@code [0-9a-f]}；</li>
 *   <li>合法长度：{@code 8 <= len <= 64}；</li>
 *   <li>缺失（无头或头值为空白）：服务端生成新标识，结果记为 {@link TraceIdResolution#GENERATED}；</li>
 *   <li>存在但非法：丢弃脏值、生成新标识，结果记为 {@link TraceIdResolution#REJECTED}，
 *       响应头只回写服务端生成的干净值，脏值绝不透传。</li>
 * </ul>
 */
public final class TraceIds {

    public static final String HEADER_NAME = "X-Trace-Id";

    /** 32 位无横线 UUID 是合法集合的一个子集，因此一定能通过 {@link #isValid}。 */
    private static final Pattern VALID = Pattern.compile("[0-9a-f]{8,64}");

    private TraceIds() {
    }

    /** 标识是否合法：小写十六进制字符，长度 8~64。 */
    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        return VALID.matcher(value).matches();
    }

    /** 生成一个新的合法标识（32 位无横线 UUID）。 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
