package com.github.h3yow.requesttracealertingjava.trace;

/**
 * 链路标识的来源结果。
 */
public enum TraceIdResolution {
    /** 请求未携带标识，服务端新生成。 */
    GENERATED,
    /** 上游标识合法，原样沿用。 */
    PROPAGATED,
    /** 上游标识格式非法，已丢弃并重新生成。 */
    REJECTED
}
