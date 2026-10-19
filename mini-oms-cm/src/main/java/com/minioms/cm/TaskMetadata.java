package com.minioms.cm;

import java.util.List;

/** 任务元数据（快照式读取，更新走 TaskRepository）。 */
public record TaskMetadata(
        String id,
        String sourceUrl, String sourceUser, String sourcePassword,
        String targetUrl, String targetUser, String targetPassword,
        String schema,
        List<String> tables,
        int chunkSize,
        int parallelism,
        long rowsPerSecond,
        TaskState state,
        boolean fullDone,
        String consistencyGtid,   // 全量前记录的一致性位点（红线 R3）
        String error,
        String createdAt,
        String updatedAt) {

    public TaskMetadata withState(TaskState newState) {
        return new TaskMetadata(id, sourceUrl, sourceUser, sourcePassword, targetUrl, targetUser, targetPassword,
                schema, tables, chunkSize, parallelism, rowsPerSecond, newState, fullDone,
                consistencyGtid, error, createdAt, updatedAt);
    }

    public TaskMetadata withStateAndError(TaskState newState, String err) {
        return new TaskMetadata(id, sourceUrl, sourceUser, sourcePassword, targetUrl, targetUser, targetPassword,
                schema, tables, chunkSize, parallelism, rowsPerSecond, newState, fullDone,
                consistencyGtid, err, createdAt, updatedAt);
    }

    public TaskMetadata withFullDone(boolean done, String gtid) {
        return new TaskMetadata(id, sourceUrl, sourceUser, sourcePassword, targetUrl, targetUser, targetPassword,
                schema, tables, chunkSize, parallelism, rowsPerSecond, state, done,
                gtid, error, createdAt, updatedAt);
    }
}
