package com.minioms.store;

import com.minioms.common.OpType;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 纯映射逻辑：PK 变更拆 DELETE+UPSERT（红线 R5）。 */
class BinlogRecordMapperTest {

    private static final List<String> COLS = List.of("id", "name", "amount");
    private static final List<String> PK = List.of("id");

    @Test
    void normalUpdateIsSingleUpsert() {
        List<com.minioms.common.RowRecord> out = BinlogRecordMapper.update(
                "db", "orders", COLS, PK,
                new Serializable[]{7L, "old", 1.0}, new Serializable[]{7L, "new", 2.0}, "s:1");
        assertEquals(1, out.size());
        assertEquals(OpType.UPSERT, out.get(0).op());
    }

    @Test
    void pkUpdateSplitsIntoDeleteThenUpsert() {
        List<com.minioms.common.RowRecord> out = BinlogRecordMapper.update(
                "db", "orders", COLS, PK,
                new Serializable[]{7L, "x", 1.0}, new Serializable[]{8L, "x", 1.0}, "s:1");
        assertEquals(2, out.size());
        assertEquals(OpType.DELETE, out.get(0).op());
        assertEquals(7L, out.get(0).values().get(0));
        assertEquals(OpType.UPSERT, out.get(1).op());
        assertEquals(8L, out.get(1).values().get(0));
    }

    @Test
    void intVsLongPkNotTreatedAsChange() {
        List<com.minioms.common.RowRecord> out = BinlogRecordMapper.update(
                "db", "orders", COLS, PK,
                new Serializable[]{7, "x", 1.0}, new Serializable[]{7L, "x", 1.0}, "s:1");
        assertEquals(1, out.size());
    }

    @Test
    void writeAndDelete() {
        assertEquals(OpType.UPSERT, BinlogRecordMapper.write(
                "db", "orders", COLS, new Serializable[]{1L, "a", 2.0}, "s:1").get(0).op());
        assertEquals(OpType.DELETE, BinlogRecordMapper.delete(
                "db", "orders", COLS, new Serializable[]{1L, "a", 2.0}, "s:1").get(0).op());
    }
}
