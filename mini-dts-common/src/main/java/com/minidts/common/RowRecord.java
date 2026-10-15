package com.minidts.common;

import java.util.List;

/**
 * 全量与增量的唯一数据契约（红线：Full 和 Store 必须输出同一模型，Connector 不得感知来源）。
 *
 * @param txId 增量：源事务标识（GTID）；全量：分片批次号
 */
public record RowRecord(
        String schema,
        String table,
        List<String> columns,
        List<Object> values,
        OpType op,
        String txId) {

    public RowRecord {
        columns = List.copyOf(columns);
        values = new java.util.ArrayList<>(values);   // 允许 NULL 单元（List.copyOf 拒绝 null）
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException("columns/values size mismatch");
        }
    }
}
