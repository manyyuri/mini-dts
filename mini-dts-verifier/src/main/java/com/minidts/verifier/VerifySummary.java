package com.minidts.verifier;

import com.minidts.common.DiffType;
import com.minidts.common.RowDiff;

import java.util.List;

/** 校验汇总。 */
public record VerifySummary(int chunks, int equalChunks, int mismatchedChunks, List<RowDiff> diffs) {

    public boolean allEqual() {
        return diffs.isEmpty();
    }
}
