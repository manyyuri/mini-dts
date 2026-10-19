package com.minioms.cm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** CM 元数据库（H2）：任务表。 */
public class TaskRepository {

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskMetadata> MAPPER = (rs, i) -> new TaskMetadata(
            rs.getString("id"),
            rs.getString("source_url"), rs.getString("source_user"), rs.getString("source_password"),
            rs.getString("target_url"), rs.getString("target_user"), rs.getString("target_password"),
            rs.getString("schema_name"),
            Arrays.stream(rs.getString("table_list").split(",")).filter(s -> !s.isBlank()).toList(),
            rs.getInt("chunk_size"), rs.getInt("parallelism"), rs.getLong("rows_per_second"),
            TaskState.valueOf(rs.getString("state")),
            rs.getBoolean("full_done"),
            rs.getString("consistency_gtid"),
            rs.getString("error"),
            rs.getString("created_at"), rs.getString("updated_at"));

    public void insert(TaskMetadata t) {
        jdbc.update("""
                INSERT INTO oms_task(id, source_url, source_user, source_password, target_url, target_user,
                  target_password, schema_name, table_list, chunk_size, parallelism, rows_per_second, state,
                  full_done, consistency_gtid, error, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                t.id(), t.sourceUrl(), t.sourceUser(), t.sourcePassword(), t.targetUrl(), t.targetUser(),
                t.targetPassword(), t.schema(), String.join(",", t.tables()), t.chunkSize(), t.parallelism(),
                t.rowsPerSecond(), t.state().name(), t.fullDone(), t.consistencyGtid(), t.error(),
                t.createdAt(), t.updatedAt());
    }

    public Optional<TaskMetadata> find(String id) {
        List<TaskMetadata> list = jdbc.query(
                "SELECT * FROM oms_task WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    /** 启动恢复：扫出所有「DB 说是运行中」的任务。 */
    public List<TaskMetadata> findByStates(TaskState... states) {
        String placeholders = String.join(",", java.util.Collections.nCopies(states.length, "?"));
        Object[] args = java.util.Arrays.stream(states).map(Enum::name).toArray();
        return jdbc.query("SELECT * FROM oms_task WHERE state IN (" + placeholders + ") ORDER BY created_at",
                MAPPER, args);
    }

    public List<TaskMetadata> findAll() {
        return jdbc.query("SELECT * FROM oms_task ORDER BY created_at", MAPPER);
    }

    public void updateState(String id, TaskState state, boolean fullDone, String error) {
        jdbc.update("UPDATE oms_task SET state = ?, full_done = ?, error = ?, updated_at = CURRENT_TIMESTAMP() WHERE id = ?",
                state.name(), fullDone, error, id);
    }

    public void updateConsistencyGtid(String id, String gtid) {
        jdbc.update("UPDATE oms_task SET consistency_gtid = ?, updated_at = CURRENT_TIMESTAMP() WHERE id = ?", gtid, id);
    }
}
