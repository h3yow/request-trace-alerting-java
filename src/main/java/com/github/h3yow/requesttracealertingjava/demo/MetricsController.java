package com.github.h3yow.requesttracealertingjava.demo;

import com.github.h3yow.requesttracealertingjava.alert.AlertEngine;
import com.github.h3yow.requesttracealertingjava.alert.AlertEvent;
import com.github.h3yow.requesttracealertingjava.metrics.EndpointStats;
import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 观测自检接口：查看当前指标快照与告警事件，供本地验证使用。
 */
@RestController
@RequestMapping("/api/observability")
public class MetricsController {

    private final MetricsRegistry metricsRegistry;
    private final AlertEngine alertEngine;
    private final Clock clock;

    public MetricsController(MetricsRegistry metricsRegistry, AlertEngine alertEngine,
                             Clock clock) {
        this.metricsRegistry = metricsRegistry;
        this.alertEngine = alertEngine;
        this.clock = clock;
    }

    @GetMapping("/metrics")
    public Map<String, EndpointStats> metrics() {
        return metricsRegistry.endpointStats(clock.millis());
    }

    @GetMapping("/alerts")
    public List<AlertEvent> alerts() {
        return alertEngine.events();
    }
}
