package com.minidts.cm;

import com.minidts.common.FullCheckpointStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;

/** 全量分片断点（CM 元数据库；语义：数据先落目标库，断点后写）。 */
public class FullCheckpointRepository implements FullCheckpointStore {

    private final JdbcTemplate jdbc;

    public FullCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void markChunkDone(String taskId, String upperPk) {
        jdbc.update("MERGE INTO dts_full_checkpoint(task_id, upper_pk, done_at) KEY(task_id, upper_pk) VALUES (?, ?, CURRENT_TIMESTAMP())",
                taskId, upperPk);
    }

    @Override
    public Set<String> completedUpperPks(String taskId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT upper_pk FROM dts_full_checkpoint WHERE task_id = ?", String.class, taskId));
    }

    public long countDone(String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM dts_full_checkpoint WHERE task_id = ?", Long.class, taskId);
    }

    public void clear(String taskId) {
        jdbc.update("DELETE FROM dts_full_checkpoint WHERE task_id = ?", taskId);
    }
}
