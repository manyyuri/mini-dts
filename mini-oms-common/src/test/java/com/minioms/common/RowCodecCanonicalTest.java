package com.minioms.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 跨方言 canonical 编码：**同一个逻辑值，无论由哪个驱动读出来，都必须编成同一段字节**。
 *
 * 这是 CRC 校验能不能跨库的前提。每一条断言都对应一次真实的「假差异」事故。
 */
class RowCodecCanonicalTest {

    private static long crc(Object mysqlFlavor, Object pgFlavor) {
        String a = RowCodec.encode(List.of("c"), List.of(mysqlFlavor));
        String b = RowCodec.encode(List.of("c"), List.of(pgFlavor));
        assertEquals(a, b, "两端应编成同一串：" + a + " vs " + b);
        return RowCodec.crc32(a);
    }

    @Test
    void decimalScaleIsIgnored() {
        // MySQL DECIMAL(10,2) 读 1.00；PG numeric 可能读 1.0 / 1
        crc(new BigDecimal("1.00"), new BigDecimal("1.0"));
        crc(new BigDecimal("1.0000"), new BigDecimal("1"));
        crc(new BigDecimal("100.00"), new BigDecimal("100"));
        assertEquals("N:1", RowCodec.cell(new BigDecimal("1.000")));
        assertEquals("N:100", RowCodec.cell(new BigDecimal("100.00")), "去尾零不能变成科学计数法");
    }

    @Test
    void integerTypesCollapseToLong() {
        crc(1, 1L);
        crc((short) 1, 1L);
        crc((byte) 1, 1L);
    }

    @Test
    void booleanEqualsTinyintOne() {
        // PG boolean true 与 MySQL tinyint(1)=1
        crc(true, 1);
        crc(false, 0);
    }

    @Test
    void timeTypesWithSameInstantCollapse() {
        LocalDateTime local = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        Timestamp mysql = Timestamp.valueOf(local);
        OffsetDateTime pgTimestamptz = local.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        crc(mysql, pgTimestamptz);
        crc(mysql, pgTimestamptz.toInstant());
        crc(mysql, local);
    }

    @Test
    void dateAndUuidAreStable() {
        crc(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        UUID uuid = UUID.fromString("3E11FA47-71CA-11E1-9E33-C80AA9429562");
        assertEquals("S:" + uuid, RowCodec.cell(uuid.toString()));
    }

    @Test
    void nullStaysNull() {
        assertEquals(RowCodec.NULL_SENTINEL, RowCodec.cell(null));
    }

    @Test
    void genuinelyDifferentValuesStillDiffer() {
        assertNotEquals(RowCodec.cell(new BigDecimal("1.01")), RowCodec.cell(new BigDecimal("1.1")));
        assertNotEquals(RowCodec.cell(true), RowCodec.cell(2));
        assertNotEquals(RowCodec.cell("1"), RowCodec.cell(1), "字符串 '1' 与数字 1 不是同一值");
    }

    @Test
    void nanosecondPrecisionIsPreservedNotTruncated() {
        LocalDateTime withNanos = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123_000_000);
        LocalDateTime noNanos = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        assertNotEquals(RowCodec.cell(withNanos), RowCodec.cell(noNanos), "datetime(3) 不能被截成秒");
    }
}
