package com.minidts.cm;

import java.util.List;
import java.util.Map;

/**
 * 控制面端口（starter 装配数据面实现；cm 只面向接口编程）。
 */
public interface MigrationEngine {

    /** 创建任务（含环境预检 + 主键一致性校验），失败抛 DtsException。 */
    String createTask(TaskCreateRequest request);

    /** 启动：INIT→全量；全量完成→增量。幂等（已运行则拒绝）。 */
    void start(String taskId);

    void pause(String taskId);

    /** 从 PAUSED/ERROR 恢复（按记录的阶段续跑：断点续传）。 */
    void resume(String taskId);

    /** 增量期收尾：停止订阅（目标库此时已追平）。 */
    void finish(String taskId);

    /** 触发一次 CRC 对账。 */
    Map<String, Object> verify(String taskId);

    /** 运行指标。 */
    Map<String, Object> metrics(String taskId);

    record TaskCreateRequest(
            String sourceUrl, String sourceUser, String sourcePassword,
            String targetUrl, String targetUser, String targetPassword,
            String schema, List<String> tables,
            int chunkSize, int parallelism, long rowsPerSecond) {}
}
