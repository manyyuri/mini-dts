package com.minioms.common;

import java.util.List;

/** 校验报告落库端口（cm 模块实现）。 */
public interface VerifyReportStore {

    void saveChunkResult(String taskId, int chunkIndex, String chunkUpper,
                         boolean equal, long srcCrc, long tgtCrc, List<RowDiff> diffs);

    void clearTask(String taskId);
}
