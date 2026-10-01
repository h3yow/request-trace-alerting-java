package com.github.h3yow.requesttracealertingjava.alert;

import com.github.h3yow.requesttracealertingjava.metrics.RequestSample;

import java.util.List;
import java.util.Locale;

/**
 * 对某个规则在某个时刻、基于给定样本的纯函数判定结果。
 *
 * <p>判定口径（可复核、可解释）：
 * <ol>
 *   <li>窗口：只取 {@code startMs in (nowMs - rule.windowMs, nowMs]} 的样本；</li>
 *   <li>数据充分性：样本数 &lt; {@code rule.minSamples} 判为数据不足，既不算正常也不算异常，
 *       不参与连续异常计数，避免低流量下误报；</li>
 *   <li>异常条件：命中比例 &gt; {@code rule.threshold}（严格大于，等于阈值不算异常）；</li>
 *   <li>错误/慢的定义复用指标侧的样本标记，口径不允许在告警侧重解释。</li>
 * </ol>
 */
public record AlertVerdict(boolean healthy, boolean dataInsufficient, long sampleCount,
                           long matchedCount, double ratio, double threshold, String reason) {

    /**
     * 纯函数：同一份样本 + 同一份规则，结论必然一致（不依赖任何可变状态/墙上时钟）。
     *
     * @param nowMs 由调用方注入评估时刻，使历史数据可被重复评估并得到一致结论
     */
    public static AlertVerdict evaluate(AlertRule rule, List<RequestSample> samples, long nowMs) {
        long windowStart = nowMs - rule.windowMs();
        long matched = 0;
        long count = 0;
        for (RequestSample sample : samples) {
            if (sample.startMs() <= windowStart || sample.startMs() > nowMs) {
                continue;
            }
            count++;
            boolean hit = rule.metric() == AlertMetric.ERROR_RATE ? sample.error() : sample.slow();
            if (hit) {
                matched++;
            }
        }

        if (count < rule.minSamples()) {
            String reason = String.format(Locale.ROOT,
                    "数据不足不告警: windowMs=%d 样本数=%d < minSamples=%d",
                    rule.windowMs(), count, rule.minSamples());
            return new AlertVerdict(false, true, count, matched, ratio(matched, count),
                    rule.threshold(), reason);
        }

        double ratio = ratio(matched, count);
        boolean unhealthy = ratio > rule.threshold();
        String metricName = rule.metric() == AlertMetric.ERROR_RATE ? "错误率" : "慢请求率";
        String reason;
        if (unhealthy) {
            reason = String.format(Locale.ROOT,
                    "%s超阈值: %s=%.4f > threshold=%.4f (命中=%d/样本=%d, windowMs=%d, 严格大于判定)",
                    metricName, metricName, ratio, rule.threshold(), matched, count, rule.windowMs());
        } else {
            reason = String.format(Locale.ROOT,
                    "%s未超阈值: %s=%.4f <= threshold=%.4f (命中=%d/样本=%d, windowMs=%d)",
                    metricName, metricName, ratio, rule.threshold(), matched, count, rule.windowMs());
        }
        return new AlertVerdict(!unhealthy, false, count, matched, ratio, rule.threshold(), reason);
    }

    private static double ratio(long matched, long count) {
        return count == 0 ? 0.0 : (double) matched / count;
    }
}
