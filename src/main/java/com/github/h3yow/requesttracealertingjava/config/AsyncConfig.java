package com.github.h3yow.requesttracealertingjava.config;

import com.github.h3yow.requesttracealertingjava.trace.TraceAwareTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器配置：所有 @Async 任务经由 {@link TraceAwareTaskDecorator} 传递链路标识。
 *
 * <p>队列设置有界（容量 200），拒绝策略 AbortPolicy 而非无限堆积，
 * 保证长时间运行下内存占用有上界。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "traceTaskExecutor")
    public Executor traceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(new TraceAwareTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
