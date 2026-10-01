package com.github.h3yow.requesttracealertingjava.alert;

/**
 * 固定桶数的滑动窗口。
 *
 * <p>内存上界：桶数组大小在构造时固定，不随运行时间增长。
 * 确定性：聚合只取决于桶内数据与入参 nowMillis，
 * 同样的历史数据用同样的 nowMillis 重评估，结论一致。
 */
public class SlidingWindow {

    private final WindowBucket[] buckets;
    private final long bucketMillis;

    public SlidingWindow(int bucketCount, long bucketMillis) {
        if (bucketCount <= 0 || bucketMillis <= 0) {
            throw new IllegalArgumentException("bucketCount and bucketMillis must be positive");
        }
        this.buckets = new WindowBucket[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            this.buckets[i] = new WindowBucket();
        }
        this.bucketMillis = bucketMillis;
    }

    public synchronized void record(long timestampMillis, boolean error, boolean slow) {
        long aligned = align(timestampMillis);
        buckets[slot(aligned)].recordAt(aligned, error, slow);
    }

    /** 聚合 (nowMillis - windowMillis, nowMillis] 区间内的数据（含当前时刻）。 */
    public synchronized WindowStats aggregate(long nowMillis, long windowMillis) {
        long from = nowMillis - windowMillis;
        long total = 0;
        long errors = 0;
        long slows = 0;
        for (WindowBucket bucket : buckets) {
            long start = bucket.getBucketStartMillis();
            if (start == Long.MIN_VALUE || start <= from || start > nowMillis) {
                continue;
            }
            total += bucket.getTotal();
            errors += bucket.getErrors();
            slows += bucket.getSlows();
        }
        return new WindowStats(total, errors, slows);
    }

    private long align(long timestampMillis) {
        return (timestampMillis / bucketMillis) * bucketMillis;
    }

    private int slot(long alignedStartMillis) {
        return (int) Math.floorMod(alignedStartMillis / bucketMillis, buckets.length);
    }

    /** 窗口聚合结果（不可变快照）。 */
    public record WindowStats(long total, long errors, long slows) {
        public double errorRate() {
            return total == 0 ? 0.0 : (double) errors / total;
        }
    }
}
