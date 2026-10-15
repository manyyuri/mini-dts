package com.minidts.cm;

import org.springframework.jdbc.core.JdbcTemplate;

/** CM 元数据表结构（H2，启动时建）。 */
public class SchemaInit {

    public static void init(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS dts_task (
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
                  consistency_gtid CLOB,
                  error CLOB,
                  created_at TIMESTAMP,
                  updated_at TIMESTAMP
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS dts_full_checkpoint (
                  task_id VARCHAR(64),
                  upper_pk VARCHAR(256),
                  done_at TIMESTAMP,
                  CONSTRAINT pk_full_checkpoint PRIMARY KEY (task_id, upper_pk)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS dts_verify_chunk (
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
                CREATE TABLE IF NOT EXISTS dts_verify_diff (
                  task_id VARCHAR(64),
                  chunk_index INT,
                  pk VARCHAR(256),
                  diff_type VARCHAR(32),
                  src_row CLOB,
                  tgt_row CLOB,
                  found_at TIMESTAMP
                )""");
    }
}
