package com.minidts.common;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结构指纹：建任务时登记、增量启动时复核，用来挡住「源端被手工改结构」的静默漂移。 */
class SchemaFingerprintTest {

    @Test
    void fingerprintIsOrderAndTypeSensitive() {
        String a = SchemaFingerprint.of(List.of("id:BIGINT:NO", "name:VARCHAR:YES"));
        assertEquals(a, SchemaFingerprint.of(List.of("id:BIGINT:NO", "name:VARCHAR:YES")));
        assertNotEquals(a, SchemaFingerprint.of(List.of("name:VARCHAR:YES", "id:BIGINT:NO")), "顺序变了就是结构变了");
        assertNotEquals(a, SchemaFingerprint.of(List.of("id:BIGINT:NO", "name:TEXT:YES")), "类型变了要能发现");
        assertNotEquals(a, SchemaFingerprint.of(List.of("id:BIGINT:NO")), "少一列要能发现");
    }

    @Test
    void readsFingerprintAndDetectsAddColumn() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:fp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            }
            String before = SchemaFingerprint.read(conn, null, "orders");
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE orders ADD COLUMN amount DECIMAL(10,2)");
            }
            String after = SchemaFingerprint.read(conn, null, "orders");
            assertNotEquals(before, after);
            assertTrue(SchemaFingerprint.columns(conn, null, "orders").stream()
                    .anyMatch(c -> c.startsWith("amount")));
        }
    }

    @Test
    void missingTableFailsLoudly() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:fp2;MODE=MySQL", "sa", "")) {
            assertThrows(DtsException.class, () -> SchemaFingerprint.read(conn, null, "nope"));
        }
    }
}
