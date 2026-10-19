package com.minidts.common;

/** 行/秒限流端口：全量读、增量回放共用（可组合出全局 + 任务级双层预算）。 */
public interface RateLimiter {

    /** 申请 n 行的预算，必要时阻塞。 */
    void acquire(long permits);

    /** 不做限流。 */
    RateLimiter UNLIMITED = permits -> {
    };
}
