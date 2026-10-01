package com.github.h3yow.requesttracealertingjava.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单接口的告警评估器（状态机）。
 *
 * <p>判定口径：
 * <ul>
 *   <li>窗口内请求数 &lt; minRequests 时样本不足，不改变告警状态；</li>
 *   <li>异常条件：错误率 &gt; maxErrorRate，或慢请求数 &gt; maxSlowCount；</li>
 *   <li>连续 consecutiveWindows 次评估都异常才触发告警；</li>
 *   <li>触发后进入 suppressionMillis 抑制窗口，期间不重复触发；</li>
 *   <li>告警状态下评估恢复正常（条件不再满足）即发出恢复事件。</li>
 * </ul>
 *
 * <p>确定性：{@link #evaluate(WindowSnapshot, AlertRule)} 是纯函数，
 * 同一份窗口数据用同一条规则重评估，结论与判定依据完全一致；
 * 规则更新只影响更新之后的评估，不回溯改写历史结论。
 */
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final String endpoint;
    private final SlidingWindow window;
    private final AlertSink sink;

    private volatile AlertRule rule;
    private int consecutiveBreaches;
    private boolean alerting;
    // 用 Long.MIN_VALUE / 2 表示“从未触发”，避免与当前时间相减时溢出
    private long lastTriggerMillis = Long.MIN_VALUE / 2;
    private long lastEvaluatedBucket = Long.MIN_VALUE;

    public AlertEvaluator(String endpoint, AlertRule rule, AlertSink sink) {
        this.endpoint = endpoint;
        // 桶数固定：窗口长度 / 桶长 + 1 个缓冲桶，保证覆盖整个窗口
        int bucketCount = (int) (rule.windowSizeMillis() / 1_000) + 2;
        this.window = new SlidingWindow(Math.max(bucketCount, 8), 1_000);
        this.rule = rule;
        this.sink = sink;
    }

    public AlertRule getRule() {
        return rule;
    }

    /** 更新规则，只影响之后的评估，历史窗口数据保持不变。 */
    public void updateRule(AlertRule newRule) {
        this.rule = newRule;
    }

    /**
     * 记录一次请求；每个时间桶只在首次落入时评估一次，
     * 因此“连续 N 次异常”对应连续 N 个桶的评估，而不是连续 N 条记录。
     */
    public synchronized void recordAndEvaluate(long timestampMillis, boolean error, boolean slow) {
        window.record(timestampMillis, error, slow);
        long bucket = timestampMillis / 1_000;
        if (bucket == lastEvaluatedBucket) {
            return;
        }
        lastEvaluatedBucket = bucket;

        AlertRule current = rule;
        WindowSnapshot snapshot = new WindowSnapshot(window.aggregate(timestampMillis, current.windowSizeMillis()));
        if (snapshot.stats().total() < current.minRequests()) {
            // 样本不足：不改变告警状态（既不累计异常，也不判定恢复）
            log.debug("evaluate endpoint={} bucket={} skipped: sample too small total={}",
                    endpoint, bucket, snapshot.stats().total());
            return;
        }
        Decision decision = evaluate(snapshot, current);
        log.debug("evaluate endpoint={} bucket={} breach={} reason={}", endpoint, bucket, decision.breach(), decision.reason());

        if (decision.breach()) {
            consecutiveBreaches++;
        } else {
            if (alerting) {
                alerting = false;
                consecutiveBreaches = 0;
                sink.emit(new AlertEvent(AlertEvent.Type.RECOVERED, endpoint,
                        decision.reason(), snapshot.stats(), timestampMillis));
            } else {
                consecutiveBreaches = 0;
            }
            return;
        }

        if (!alerting
                && consecutiveBreaches >= current.consecutiveWindows()
                && timestampMillis - lastTriggerMillis >= current.suppressionMillis()) {
            alerting = true;
            lastTriggerMillis = timestampMillis;
            sink.emit(new AlertEvent(AlertEvent.Type.TRIGGERED, endpoint,
                    decision.reason(), snapshot.stats(), timestampMillis));
        }
    }

    /** 当前窗口的只读快照，用于测试与重评估。 */
    public synchronized WindowSnapshot snapshotAt(long nowMillis) {
        return new WindowSnapshot(window.aggregate(nowMillis, rule.windowSizeMillis()));
    }

    /**
     * 纯函数判定：同样的窗口数据 + 同样的规则 ⇒ 同样的结论与依据。
     */
    public static Decision evaluate(WindowSnapshot snapshot, AlertRule rule) {
        SlidingWindow.WindowStats stats = snapshot.stats();
        if (stats.total() < rule.minRequests()) {
            return new Decision(false,
                    "sample too small: total=" + stats.total() + " < minRequests=" + rule.minRequests());
        }
        StringBuilder reason = new StringBuilder();
        boolean breach = false;
        if (stats.errorRate() > rule.maxErrorRate()) {
            breach = true;
            reason.append(String.format("errorRate=%.3f > %.3f", stats.errorRate(), rule.maxErrorRate()));
        }
        if (stats.slows() > rule.maxSlowCount()) {
            breach = true;
            if (reason.length() > 0) {
                reason.append("; ");
            }
            reason.append("slowCount=").append(stats.slows()).append(" > ").append(rule.maxSlowCount());
        }
        if (!breach) {
            reason.append(String.format("within thresholds: errorRate=%.3f<=%.3f, slowCount=%d<=%d",
                    stats.errorRate(), rule.maxErrorRate(), stats.slows(), rule.maxSlowCount()));
        }
        return new Decision(breach, reason.toString());
    }

    /** 窗口数据快照。 */
    public record WindowSnapshot(SlidingWindow.WindowStats stats) {
    }

    /** 判定结论与可读依据。 */
    public record Decision(boolean breach, String reason) {
    }
}
