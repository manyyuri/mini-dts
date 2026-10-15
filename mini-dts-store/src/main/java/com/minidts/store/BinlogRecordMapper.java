package com.minidts.store;

import com.minidts.common.OpType;
import com.minidts.common.RowRecord;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * binlog 行事件 → RowRecord 的纯映射逻辑（可单测，不依赖网络/真实 binlog）。
 *
 * 红线 R5：UPDATE 的主键变更 = DELETE 旧行 + UPSERT 新行（同表顺序保持）。
 */
public final class BinlogRecordMapper {

    private BinlogRecordMapper() {}

    public static List<RowRecord> write(String schema, String table, List<String> columns,
                                        Serializable[] after, String txId) {
        return List.of(record(schema, table, columns, after, OpType.UPSERT, txId));
    }

    public static List<RowRecord> delete(String schema, String table, List<String> columns,
                                         Serializable[] before, String txId) {
        return List.of(record(schema, table, columns, before, OpType.DELETE, txId));
    }

    public static List<RowRecord> update(String schema, String table, List<String> columns,
                                         List<String> pkColumns, Serializable[] before, Serializable[] after,
                                         String txId) {
        List<RowRecord> out = new ArrayList<>(2);
        if (pkChanged(pkColumns, columns, before, after)) {
            out.add(record(schema, table, columns, before, OpType.DELETE, txId));
            out.add(record(schema, table, columns, after, OpType.UPSERT, txId));
        } else {
            out.add(record(schema, table, columns, after, OpType.UPSERT, txId));
        }
        return out;
    }

    private static boolean pkChanged(List<String> pkColumns, List<String> columns,
                                     Serializable[] before, Serializable[] after) {
        if (pkColumns == null || pkColumns.isEmpty()) return false;
        for (String pk : pkColumns) {
            int idx = columns.indexOf(pk);
            if (idx < 0 || idx >= before.length || idx >= after.length) return false;
            Object b = before[idx];
            Object a = after[idx];
            if (b == null || a == null ? b != a : !normalizeKey(b).equals(normalizeKey(a))) {
                return true;
            }
        }
        return false;
    }

    /** 整数族在 binlog 反序列化里可能是 Integer/Short/Long——归一成 Long 再比。 */
    private static Object normalizeKey(Object v) {
        if (v instanceof Integer || v instanceof Short || v instanceof Byte || v instanceof Long) {
            return ((Number) v).longValue();
        }
        return v;
    }

    private static RowRecord record(String schema, String table, List<String> columns,
                                    Serializable[] values, OpType op, String txId) {
        List<Object> vals = new ArrayList<>(values.length);
        for (Serializable v : values) vals.add(v);
        return new RowRecord(schema, table, columns, vals, op, txId);
    }
}
