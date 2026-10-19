package com.minidts.common;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** 令牌桶限流：保护源库读取速率（行/秒）。容量 = 速率，允许 1 秒突发。 */
public final class TokenBucket implements RateLimiter {

    private final long capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefillNanos;
    private final Lock lock = new ReentrantLock();

    public TokenBucket(long rowsPerSecond) {
        this.capacity = Math.max(1, rowsPerSecond);
        this.refillPerNano = capacity / 1e9;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public long rowsPerSecond() {
        return capacity;
    }

    @Override
    public void acquire(long n) {
        if (n <= 0) {
            return;
        }
        lock.lock();
        try {
            refill();
            while (tokens < n) {
                lock.unlock();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                lock.lock();
                refill();
            }
            tokens -= n;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * refillPerNano);
        lastRefillNanos = now;
    }
}
