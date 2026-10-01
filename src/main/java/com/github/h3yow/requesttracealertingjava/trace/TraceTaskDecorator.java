package com.github.h3yow.requesttracealertingjava.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 异步任务装饰器：任务提交时抓取链路上下文快照，
 * 执行时在异步线程恢复，执行完清理，保证异步边界两侧链路标识一致。
 */
public class TraceTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TraceContext.TraceSnapshot snapshot = TraceContext.snapshot();
        String mdcTraceId = MDC.get(TraceIdFilter.MDC_KEY);
        return () -> {
            TraceContext.restore(snapshot);
            if (mdcTraceId != null) {
                MDC.put(TraceIdFilter.MDC_KEY, mdcTraceId);
            }
            try {
                runnable.run();
            } finally {
                TraceContext.clear();
                MDC.remove(TraceIdFilter.MDC_KEY);
            }
        };
    }
}
