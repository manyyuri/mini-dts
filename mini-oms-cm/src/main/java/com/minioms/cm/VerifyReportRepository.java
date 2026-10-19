package com.minioms.cm;

import com.minioms.common.DiffType;
import com.minioms.common.RowDiff;
import com.minioms.common.VerifyReportStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 校验差异落库（CM 元数据库）。 */
public class VerifyReportRepository implements VerifyReportStore {

    private final JdbcTemplate jdbc;

    public VerifyReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveChunkResult(String taskId, int chunkIndex, String chunkUpper,
                                boolean equal, long srcCrc, long tgtCrc, List<RowDiff> diffs) {
        // UPDATE-then-INSERT：H2 与 MySQL 都认，不依赖 MERGE / ON DUPLICATE 方言
        int updated = jdbc.update("""
                UPDATE oms_verify_chunk SET chunk_upper = ?, equal = ?, src_crc = ?, tgt_crc = ?, checked_at = ?
                WHERE task_id = ? AND chunk_index = ?
                """, chunkUpper, equal, srcCrc, tgtCrc, new java.sql.Timestamp(System.currentTimeMillis()),
                taskId, chunkIndex);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO oms_verify_chunk(task_id, chunk_index, chunk_upper, equal, src_crc, tgt_crc, checked_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, taskId, chunkIndex, chunkUpper, equal, srcCrc, tgtCrc,
                    new java.sql.Timestamp(System.currentTimeMillis()));
        }
        if (diffs != null && !diffs.isEmpty()) {
            for (RowDiff d : diffs) {
                jdbc.update("""
                        INSERT INTO oms_verify_diff(task_id, chunk_index, pk, diff_type, src_row, tgt_row, found_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, taskId, chunkIndex, d.pk(), d.type().name(), d.srcRow(), d.tgtRow(),
                        new java.sql.Timestamp(System.currentTimeMillis()));
            }
        }
    }

    @Override
    public void clearTask(String taskId) {
        jdbc.update("DELETE FROM oms_verify_diff WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM oms_verify_chunk WHERE task_id = ?", taskId);
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    public int diffCount(String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_verify_diff WHERE task_id = ?", Integer.class, taskId);
    }

    public int equalChunkCount(String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_verify_chunk WHERE task_id = ? AND equal = TRUE", Integer.class, taskId);
    }

    public int totalChunkCount(String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_verify_chunk WHERE task_id = ?", Integer.class, taskId);
    }

    public List<String> recentDiffs(String taskId, int limit) {
        // 字符串拼接各家方言不一样（|| vs CONCAT），干脆回 Java 拼
        return jdbc.query("SELECT pk, diff_type, src_row, tgt_row FROM oms_verify_diff"
                        + " WHERE task_id = ? ORDER BY found_at DESC LIMIT ?",
                (rs, i) -> rs.getString(1) + " | " + rs.getString(2)
                        + " | src=" + nullSafe(rs.getString(3)) + " | tgt=" + nullSafe(rs.getString(4)),
                taskId, limit);
    }
}
