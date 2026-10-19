package com.minioms.common;

/**
 * 一条 DDL 事件的现场记录（binlog QUERY 事件里读到的结构变更）。
 *
 * @param kind     分类结果（见 store 模块 DdlClassifier）
 * @param schema   源库名（取自 QUERY 事件的 database）
 * @param table    受影响表（解析不出时为 null）
 * @param sql      原始语句（供人工在目标端执行）
 * @param gtid     产生该 DDL 的 GTID
 * @param applied  是否已由本系统在目标端自动执行（白名单 DDL 才会为 true）
 * @param reason   未自动执行 / 执行失败的原因
 */
public record DdlEvent(String taskId, String kind, String schema, String table, String sql,
                       String gtid, boolean applied, String reason) {
}
