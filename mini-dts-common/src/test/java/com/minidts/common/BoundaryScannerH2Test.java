package com.minidts.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** H2(MySQL 模式) 上的主键解析 + 边界扫描（chunk 切分正确性）。 */
class BoundaryScannerH2Test {

    Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:bs;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, note VARCHAR(50))");
            for (long i = 1; i <= 25; i++) {
                st.execute("INSERT INTO orders VALUES (" + i + ", 'n" + i + "')");
            }
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    void resolvePkOnH2() {
        List<String> pks = PkResolver.resolvePkColumns(conn, "public", "orders");
        assertEquals(List.of("id"), pks);
        assertEquals("bigint", PkResolver.pkTypeName(conn, "public", "orders").toLowerCase());
    }

    @Test
    void chunkBoundariesDivisible() throws Exception {
        List<Object> uppers = JdbcPkBoundaryScanner.scanUpperBounds(conn, "public", "orders", "id", 5);
        assertEquals(List.of(5L, 10L, 15L, 20L, 25L), uppers);
    }

    @Test
    void chunkBoundariesWithRemainder() throws Exception {
        List<Object> uppers = JdbcPkBoundaryScanner.scanUpperBounds(conn, "public", "orders", "id", 10);
        assertEquals(List.of(10L, 20L, 25L), uppers);
    }

    @Test
    void emptyTable() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE empty_t (id BIGINT PRIMARY KEY)");
        }
        assertEquals(List.of(), JdbcPkBoundaryScanner.scanUpperBounds(conn, "public", "empty_t", "id", 5));
    }

    @Test
    void noPkTableRejected() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE nopk (a INT)");
        }
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(DtsException.class,
                () -> PkResolver.resolvePkColumns(conn, "public", "nopk"));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("无主键"));
    }
}
