package com.github.h3yow.requesttracealertingjava.trace;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步执行器配置：挂载 {@link TraceTaskDecorator}，
 * 让 @Async 方法继承调用方的链路上下文。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String EXECUTOR = "traceAwareExecutor";

    @Bean(EXECUTOR)
    public Executor traceAwareExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(new TraceTaskDecorator());
        executor.initialize();
        return executor;
    }
}
