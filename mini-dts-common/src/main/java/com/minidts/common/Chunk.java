package com.minidts.common;

/**
 * 主键范围分片：(lowerExclusive, upperInclusive]。
 * 全量与校验共用同一分片模型（红线 R6）。
 */
public record Chunk(int shardNo, Object lowerExclusive, Object upperInclusive) {

    public String upperKey() {
        return PkCodec.encode(upperInclusive);
    }
}
