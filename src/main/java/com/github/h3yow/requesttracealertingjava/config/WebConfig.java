package com.github.h3yow.requesttracealertingjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Web/观测相关配置入口：启用 {@link TraceAlertProperties} 的配置绑定。
 */
@Configuration
@EnableConfigurationProperties(TraceAlertProperties.class)
public class WebConfig {
}
