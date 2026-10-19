package com.minidts.common;

/**
 * 数据面 → 控制面回调端口（starter 装配时实现；full/store 模块不依赖 Spring）。
 *
 * 三个方法对应三类完全不同的收尾动作，不能合并：
 * - onFullDone：正常推进
 * - onError：终态失败（重连耗尽、写入持续失败）
 * - onSchemaChange：源端结构变了，业务无法自行判断，必须先停下来等人
 */
public interface TaskStateNotifier {

    /** 全量分片全部完成。 */
    void onFullDone(String taskId);

    /** 数据面失败，任务转 ERROR。 */
    void onError(String taskId, String reason);

    /** 源端 DDL 变更需要人工确认，任务转 PAUSED（默认降级为 ERROR，宁可明显也不要静默）。 */
    default void onSchemaChange(String taskId, String detail) {
        onError(taskId, "源端结构变更需人工确认: " + detail);
    }
}
