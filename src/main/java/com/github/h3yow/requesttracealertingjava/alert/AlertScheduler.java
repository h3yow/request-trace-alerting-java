package com.github.h3yow.requesttracealertingjava.alert;

import com.github.h3yow.requesttracealertingjava.config.TraceAlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 周期性驱动 {@link AlertEngine} 评估。
 *
 * <p>调度本身只负责“心跳”，判定全部在引擎内完成；测试中可直接调用
 * {@link AlertEngine#evaluate(long)} 注入固定时间，不依赖真实调度。
 */
@Component
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final AlertEngine engine;
    private final Clock clock;

    public AlertScheduler(AlertEngine engine, TraceAlertProperties properties, Clock clock) {
        this.engine = engine;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${observability.alert-evaluation-interval-ms:5000}")
    public void tick() {
        try {
            engine.evaluate(clock.millis());
        } catch (Exception ex) {
            // 单轮评估失败不能杀死调度线程
            log.error("告警评估轮次异常", ex);
        }
    }
}
