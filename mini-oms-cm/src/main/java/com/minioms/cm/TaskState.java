package com.minioms.cm;

/** 任务状态（控制面）。数据面通过回调推进。 */
public enum TaskState {
    INIT,          // 已创建
    RUNNING_FULL,  // 全量进行中
    FULL_DONE,     // 全量完成（未开增量）
    RUNNING_INCR,  // 增量进行中
    PAUSED,        // 已暂停（记录当时阶段）
    ERROR,         // 失败（可修复后恢复）
    FINISHED       // 已完成（终态）
}
