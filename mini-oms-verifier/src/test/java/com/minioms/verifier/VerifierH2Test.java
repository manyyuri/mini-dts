package com.minioms.verifier;

import com.minioms.common.DiffType;
import com.minioms.common.RowDiff;
import com.minioms.common.VerifyReportStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** H2 双库校验：能区分 VALUE_DIFF / MISSING / TARGET_ONLY / 超出源最大主键的多余行。 */
class VerifierH2Test {

    Connection source;
    Connection target;
    MemoryReports reports;

    @BeforeEach
    void setUp() throws Exception {
        source = DriverManager.getConnection(
                "jdbc:h2:mem:vsrc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
        target = DriverManager.getConnection(
                "jdbc:h2:mem:vtgt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
        for (Connection c : List.of(source, target)) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))");
            }
        }
        try (Statement st = source.createStatement()) {
            for (long i = 1; i <= 50; i++) {
                st.execute("INSERT INTO orders VALUES (" + i + ", 'n" + i + "', " + i + ".25)");
            }
        }
        try (Statement st = target.createStatement()) {
            for (long i = 1; i <= 50; i++) {
                st.execute("INSERT INTO orders VALUES (" + i + ", 'n" + i + "', " + i + ".25)");
            }
        }
        reports = new MemoryReports();
    }

    @AfterEach
    void tearDown() throws SQLException {
        source.close();
        target.close();
    }

    @Test
    void identicalDatabasesVerifyClean() {
        VerifySummary s = service().verify("t1");
        assertTrue(s.allEqual());
        assertEquals(5, s.chunks());      // 50 行 / chunkSize 10
        assertEquals(5, s.equalChunks());
    }

    @Test
    void valueDiffMissingAndTargetOnlyDetected() throws Exception {
        try (Statement st = target.createStatement()) {
            st.execute("UPDATE orders SET name='WRONG' WHERE id=12");   // VALUE_DIFF
            st.execute("DELETE FROM orders WHERE id=33");                 // MISSING
            st.execute("INSERT INTO orders VALUES (99, 'extra', 1.00)");  // TARGET_ONLY 超出源最大主键
        }
        VerifySummary s = service().verify("t1");
        assertEquals(3, s.diffs().size());
        assertTrue(s.diffs().stream().anyMatch(d -> d.type() == DiffType.VALUE_DIFF && d.pk().contains("12")));
        assertTrue(s.diffs().stream().anyMatch(d -> d.type() == DiffType.MISSING && d.pk().contains("33")));
        assertTrue(s.diffs().stream().anyMatch(d -> d.type() == DiffType.TARGET_ONLY && d.pk().contains("99")));
        assertTrue(reports.saved > 0);
    }

    @Test
    void reportStoreClearedPerRun() {
        service().verify("t1");
        assertEquals(5, reports.saved);
        service().verify("t1");
        assertEquals(5, reports.saved); // clearTask 后重新记
    }

    private VerifyService service() {
        return new VerifyService(ep("vsrc"), ep("vtgt"), "public", "orders", 10, reports);
    }

    private static com.minioms.common.DbEndpoint ep(String name) {
        return new com.minioms.common.DbEndpoint(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa", "");
    }

    static class MemoryReports implements VerifyReportStore {
        int saved = 0;
        final List<RowDiff> allDiffs = new ArrayList<>();

        @Override
        public void saveChunkResult(String taskId, int chunkIndex, String chunkUpper,
                                    boolean equal, long srcCrc, long tgtCrc, List<RowDiff> diffs) {
            if (equal) saved++;
            else allDiffs.addAll(diffs);
        }

        @Override
        public void clearTask(String taskId) {
            saved = 0;
            allDiffs.clear();
        }
    }
}
