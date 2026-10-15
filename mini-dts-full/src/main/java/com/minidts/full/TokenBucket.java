package com.minidts.full;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** 简单令牌桶：保护源库读取速率（行/秒）。 */
public final class TokenBucket {

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

    public void acquire(long n) {
        if (n <= 0) return;
        lock.lock();
        try {
            tokens = Math.min(capacity, tokens + (System.nanoTime() - lastRefillNanos) * refillPerNano);
            lastRefillNanos = System.nanoTime();
            while (tokens < n) {
                lock.unlock();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                lock.lock();
                tokens = Math.min(capacity, tokens + (System.nanoTime() - lastRefillNanos) * refillPerNano);
                lastRefillNanos = System.nanoTime();
            }
            tokens -= n;
        } finally {
            lock.unlock();
        }
    }
}
