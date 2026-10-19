package com.minioms.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 全局限流器：两层预算都要扣（全局兜底 + 任务级精细），且不能互相绕过。 */
class GlobalRateLimiterTest {

    @Test
    void taskLimiterDrawsFromBothBudgets() {
        GlobalRateLimiter global = new GlobalRateLimiter(1_000);
        AtomicCounter counter = new AtomicCounter();
        RateLimiter task = global.forTask(1_000);
        RateLimiter wrapped = permits -> {
            task.acquire(permits);
            counter.add(permits);
        };

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            wrapped.acquire(400);     // 2000 行总额度，超出后必然被限速
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2_000, counter.value());
        assertTrue(elapsedMillis > 100, "超过预算必须被限速，实际耗时 " + elapsedMillis + "ms");
        assertEquals(1_000, global.globalRowsPerSecond());
    }

    private static final class AtomicCounter {
        private long value;

        void add(long n) {
            value += n;
        }

        long value() {
            return value;
        }
    }
}
