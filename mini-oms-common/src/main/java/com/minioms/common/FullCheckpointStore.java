package com.minioms.common;

import java.util.Set;

/**
 * 全量断点端口（cm 模块实现；full 模块只依赖本接口）。
 * 语义：先写目标库数据成功，再 markDone —— 顺序不可反（断点提前落 = 丢数据窗口）。
 */
public interface FullCheckpointStore {

    /** 记录一个分片（以 chunk 上限主键为键）已成功写入目标库。 */
    void markChunkDone(String taskId, String upperPk);

    /** 已完成的分片上限集合；恢复时跳过这些 chunk。 */
    Set<String> completedUpperPks(String taskId);
}
