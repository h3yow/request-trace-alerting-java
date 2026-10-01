package com.github.h3yow.requesttracealertingjava.alert;

/**
 * 不可变告警规则。
 *
 * @param id              规则唯一标识
 * @param endpoint        作用的接口维度键
 * @param metric          判定指标
 * @param threshold       触发阈值（0~1 之间的比例，判定使用严格大于）
 * @param minSamples      窗口内最少样本数，不足则数据不充分，不告警
 * @param windowMs        滑动窗口长度（毫秒）
 * @param triggerRounds   异常状态需连续成立的评估次数
 * @param suppressionMs   触发后的抑制窗口（毫秒）
 * @param version         规则版本；更新规则时更换版本，状态重置，旧事件结论不变
 */
public record AlertRule(String id, String endpoint, AlertMetric metric, double threshold,
                        int minSamples, long windowMs, int triggerRounds, long suppressionMs,
                        long version) {
}
