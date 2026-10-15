package com.minidts.cm;

import com.minidts.common.DiffType;
import com.minidts.common.RowDiff;
import com.minidts.common.VerifyReportStore;
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
        jdbc.update("""
                MERGE INTO dts_verify_chunk(task_id, chunk_index, chunk_upper, equal, src_crc, tgt_crc, checked_at)
                KEY(task_id, chunk_index) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP())
                """, taskId, chunkIndex, chunkUpper, equal, srcCrc, tgtCrc);
        if (diffs != null && !diffs.isEmpty()) {
            for (RowDiff d : diffs) {
                jdbc.update("""
                        INSERT INTO dts_verify_diff(task_id, chunk_index, pk, diff_type, src_row, tgt_row, found_at)
                        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP())
                        """, taskId, chunkIndex, d.pk(), d.type().name(), d.srcRow(), d.tgtRow());
            }
        }
    }

    @Override
    public void clearTask(String taskId) {
        jdbc.update("DELETE FROM dts_verify_diff WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM dts_verify_chunk WHERE task_id = ?", taskId);
    }

    public int diffCount(String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM dts_verify_diff WHERE task_id = ?", Integer.class, taskId);
    }

    public int equalChunkCount(String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM dts_verify_chunk WHERE task_id = ? AND equal = TRUE", Integer.class, taskId);
    }

    public int totalChunkCount(String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM dts_verify_chunk WHERE task_id = ?", Integer.class, taskId);
    }

    public List<String> recentDiffs(String taskId, int limit) {
        return jdbc.queryForList("""
                SELECT pk || ' | ' || diff_type || ' | src=' || COALESCE(src_row,'') || ' | tgt=' || COALESCE(tgt_row,'')
                FROM dts_verify_diff WHERE task_id = ? ORDER BY found_at DESC LIMIT ?
                """, String.class, taskId, limit);
    }
}
