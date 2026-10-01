package com.github.h3yow.requesttracealertingjava.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 单个接口维度的滑动窗口样本存储。
 *
 * <p>内存上界：构造时给定 {@code capacity}，任何时刻样本数不超过该值，
 * 容量打满后新样本淘汰最老样本（{@link #add} 内置清理）。
 *
 * <p>线程安全：所有读写都在同一把锁上串行，样本复制后返回，调用方拿到的是快照。
 * 请求样本按完成顺序进入，{@code startMs} 基本单调，清理按最老端进行。
 */
public class SlidingWindow {

    private final long retentionMs;
    private final int capacity;
    private final Deque<RequestSample> samples = new ArrayDeque<>();

    public SlidingWindow(long retentionMs, int capacity) {
        if (retentionMs <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("retentionMs 与 capacity 必须为正数");
        }
        this.retentionMs = retentionMs;
        this.capacity = capacity;
    }

    /** 加入一条样本；先按时间淘汰过期样本，仍超容量时丢弃最老样本。 */
    public synchronized void add(RequestSample sample) {
        prune(sample.startMs());
        samples.addLast(sample);
        while (samples.size() > capacity) {
            samples.pollFirst();
        }
    }

    /** 淘汰开始时间不晚于 {@code nowMs - retentionMs} 的样本（窗口为严格半开区间）。 */
    public synchronized void prune(long nowMs) {
        long boundary = nowMs - retentionMs;
        while (!samples.isEmpty() && samples.peekFirst().startMs() <= boundary) {
            samples.pollFirst();
        }
    }

    /** 返回当前窗口内样本的快照（先清理过期数据）。 */
    public synchronized List<RequestSample> snapshot(long nowMs) {
        prune(nowMs);
        return new ArrayList<>(samples);
    }

    public synchronized int size() {
        return samples.size();
    }
}
