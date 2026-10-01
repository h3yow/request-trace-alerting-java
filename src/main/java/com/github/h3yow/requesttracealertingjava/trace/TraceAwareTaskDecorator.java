package com.github.h3yow.requesttracealertingjava.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskDecorator;

/**
 * 把提交任务时的链路快照带到执行线程，任务结束后清理。
 *
 * <p>关键点：
 * <ul>
 *   <li>快照在 {@link #decorate} 时（即提交线程）抓取，而不是任务执行时；</li>
 *   <li>执行线程可能本身残留上一个任务的上下文，先清理再绑定；</li>
 *   <li>try/finally 保证任务抛异常也能清理，线程归还池时不携带脏标识。</li>
 * </ul>
 */
public class TraceAwareTaskDecorator implements TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(TraceAwareTaskDecorator.class);

    @Override
    public Runnable decorate(Runnable runnable) {
        // 提交线程上抓取快照
        TraceContext.Snapshot snapshot = TraceContext.snapshot();
        return () -> {
            TraceContext.Snapshot previous = TraceContext.snapshot();
            TraceContext.clear();
            TraceContext.bindSnapshot(snapshot);
            try {
                log.debug("异步任务开始执行 traceId={} resolution={}",
                        TraceContext.currentTraceId(), TraceContext.currentResolution());
                runnable.run();
            } finally {
                TraceContext.clear();
                // 理论上池线程执行前后均应为空；恢复 previous 仅为极端嵌套场景兜底
                TraceContext.bindSnapshot(previous);
                if (previous == null) {
                    TraceContext.clear();
                }
            }
        };
    }
}
