package com.minidts.common;

import java.util.Map;

/**
 * 目标端管理端口：DDL 落地 / DDL 台账 / 结构指纹。
 *
 * 与 {@link Sink}（只管业务数据写入）分开，是因为这两类操作的事务语义完全不同：
 * 数据写入要和位点同事务（红线 R4），DDL 与指纹是旁路管理动作，必须独立连接、独立提交。
 */
public interface TargetAdmin extends AutoCloseable {

    /** 在目标库执行一条 DDL（只用于白名单语句，如 ADD COLUMN）。 */
    void applyDdl(String schema, String sql);

    /** 记录一条 DDL 事件（无论是否自动执行），返回记录 id。 */
    long journal(DdlEvent event);

    /** 目标库登记过的表结构指纹：table → fingerprint。 */
    Map<String, String> fingerprints(String taskId);

    /** 登记某任务的表结构指纹（建任务时写入，增量启动时复核）。 */
    void putFingerprints(String taskId, Map<String, String> fingerprintsByTable);

    @Override
    default void close() {
    }
}
