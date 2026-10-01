package com.github.h3yow.requesttracealertingjava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 告警相关基础设施：可注入时钟（测试可替换为固定时钟）。
 */
@Configuration
@EnableScheduling
public class AlertConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
