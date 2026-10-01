package com.github.h3yow.requesttracealertingjava.alert;

import com.github.h3yow.requesttracealertingjava.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 告警管理器：按接口维护评估器，统一慢请求判定口径，接收指标事件。
 *
 * <p>内存上界：评估器数量与指标注册表一致，超出后归入 overflow 评估器。
 * 慢请求判定：耗时 &ge; 规则的 slowThresholdMillis，全服务口径统一。
 */
public class AlertManager {

    private static final Logger log = LoggerFactory.getLogger(AlertManager.class);

    private final ConcurrentHashMap<String, AlertEvaluator> evaluators = new ConcurrentHashMap<>();
    private final AtomicInteger tracked = new AtomicInteger();
    private final int maxEndpoints;
    private final List<AlertEvent> eventLog = new CopyOnWriteArrayList<>();
    private final AlertSink sink;
    private volatile AlertRule rule;

    public AlertManager(AlertRule rule, AlertSink sink) {
        this(rule, sink, MetricsRegistry.DEFAULT_MAX_ENDPOINTS);
    }

    public AlertManager(AlertRule rule, AlertSink sink, int maxEndpoints) {
        this.rule = rule;
        this.sink = event -> {
            eventLog.add(event);
            sink.emit(event);
        };
        this.maxEndpoints = maxEndpoints;
    }

    public void record(String endpoint, long timestampMillis, long latencyMillis, boolean error) {
        AlertRule current = rule;
        boolean slow = latencyMillis >= current.slowThresholdMillis();
        evaluatorFor(endpoint, current).recordAndEvaluate(timestampMillis, error, slow);
    }

    /** 更新规则：已有评估器换新规则（只影响之后的窗口），历史数据不变。 */
    public void updateRule(AlertRule newRule) {
        this.rule = newRule;
        evaluators.forEach((endpoint, evaluator) -> evaluator.updateRule(newRule));
    }

    public AlertEvaluator evaluatorFor(String endpoint) {
        return evaluators.get(endpoint);
    }

    /** 测试用：取出并清空已产生的告警事件。 */
    public List<AlertEvent> drainEvents() {
        List<AlertEvent> events = List.copyOf(eventLog);
        eventLog.clear();
        return events;
    }

    private AlertEvaluator evaluatorFor(String endpoint, AlertRule current) {
        AlertEvaluator evaluator = evaluators.get(endpoint);
        if (evaluator != null) {
            return evaluator;
        }
        if (tracked.get() >= maxEndpoints) {
            return evaluators.computeIfAbsent(MetricsRegistry.OVERFLOW_KEY,
                    key -> new AlertEvaluator(key, current, sink));
        }
        evaluator = evaluators.computeIfAbsent(endpoint, key -> {
            tracked.incrementAndGet();
            log.info("alert evaluator created: endpoint={} rule={}", key, current);
            return new AlertEvaluator(key, current, sink);
        });
        return evaluator;
    }
}
