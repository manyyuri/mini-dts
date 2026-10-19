package com.minioms.common;

/**
 * 全局限流器：把「整个实例的总预算」和「每个任务自己的速率」叠成两级令牌桶。
 *
 * 为什么需要：每个任务的限速是老设计的全部，10 个任务各 5 万行/秒 = 源库被压 50 万行/秒，
 * 谁都没超自己的预算，源库照样被打挂。这里把实例级预算做成硬上限。
 */
public final class GlobalRateLimiter {

    private final TokenBucket global;

    public GlobalRateLimiter(long globalRowsPerSecond) {
        this.global = new TokenBucket(globalRowsPerSecond);
    }

    /** 任务级限流器：先扣全局预算，再扣任务预算（两层都满足才放行）。 */
    public RateLimiter forTask(long taskRowsPerSecond) {
        TokenBucket task = new TokenBucket(taskRowsPerSecond);
        return permits -> {
            global.acquire(permits);
            task.acquire(permits);
        };
    }

    public long globalRowsPerSecond() {
        return global.rowsPerSecond();
    }
}
