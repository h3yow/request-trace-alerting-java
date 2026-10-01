package com.github.h3yow.requesttracealertingjava.alert;

/**
 * 滑动窗口中的一个时间桶：记录该桶对齐起点及桶内的请求数、错误数、慢请求数。
 * 非线程安全，由 {@link SlidingWindow} 同步访问。
 */
public class WindowBucket {

    private long bucketStartMillis = Long.MIN_VALUE;
    private long total;
    private long errors;
    private long slows;

    /** 若桶对齐起点变化则先清零，再记录一次请求。 */
    public void recordAt(long alignedStartMillis, boolean error, boolean slow) {
        if (bucketStartMillis != alignedStartMillis) {
            bucketStartMillis = alignedStartMillis;
            total = 0;
            errors = 0;
            slows = 0;
        }
        total++;
        if (error) {
            errors++;
        }
        if (slow) {
            slows++;
        }
    }

    public long getBucketStartMillis() {
        return bucketStartMillis;
    }

    public long getTotal() {
        return total;
    }

    public long getErrors() {
        return errors;
    }

    public long getSlows() {
        return slows;
    }
}
