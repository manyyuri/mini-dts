package com.minioms.cm;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CM 元数据表结构（启动时建，幂等）。
 *
 * 迁移目标：这些表可以整体放在共享 MySQL 上 —— 所有 DDL 都只用 H2 / MySQL 双兼容语法，
 * 长文本列类型由 {@link SqlDialect} 决定。
 *
 * 注意 oms_full_checkpoint 不在这里：全量断点已迁到目标库（见 connector/TargetFullCheckpointStore），
 * 与它保护的数据同处一地，任务换节点也能接着跑。
 */
public class SchemaInit {

    public static void init(JdbcTemplate jdbc) {
        init(jdbc, SqlDialect.detect(safeDataSource(jdbc)));
    }

    public static void init(JdbcTemplate jdbc, SqlDialect dialect) {
        String longText = dialect.longText();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oms_task (
                  id VARCHAR(64) PRIMARY KEY,
                  source_url VARCHAR(256) NOT NULL,
                  source_user VARCHAR(64),
                  source_password VARCHAR(128),
                  target_url VARCHAR(256) NOT NULL,
                  target_user VARCHAR(64),
                  target_password VARCHAR(128),
                  schema_name VARCHAR(64) NOT NULL,
                  table_list VARCHAR(2048),
                  chunk_size INT,
                  parallelism INT,
                  rows_per_second BIGINT,
                  state VARCHAR(32),
                  full_done BOOLEAN,
                  consistency_gtid %s,
                  error %s,
                  created_at TIMESTAMP,
                  updated_at TIMESTAMP
                )""".formatted(longText, longText));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oms_verify_chunk (
                  task_id VARCHAR(64),
                  chunk_index INT,
                  chunk_upper VARCHAR(256),
                  equal BOOLEAN,
                  src_crc BIGINT,
                  tgt_crc BIGINT,
                  checked_at TIMESTAMP,
                  CONSTRAINT pk_verify_chunk PRIMARY KEY (task_id, chunk_index)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oms_verify_diff (
                  task_id VARCHAR(64),
                  chunk_index INT,
                  pk VARCHAR(256),
                  diff_type VARCHAR(32),
                  src_row %s,
                  tgt_row %s,
                  found_at TIMESTAMP
                )""".formatted(longText, longText));
        // 任务租约：多节点部署时谁跑同一个任务，靠它排他（不靠进程内 synchronized）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oms_task_lease (
                  task_id VARCHAR(64) PRIMARY KEY,
                  owner VARCHAR(128) NOT NULL,
                  renewed_at TIMESTAMP NOT NULL
                )""");
    }

    private static javax.sql.DataSource safeDataSource(JdbcTemplate jdbc) {
        try {
            return jdbc.getDataSource();
        } catch (Exception e) {
            return null;
        }
    }
}
