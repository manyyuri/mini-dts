package com.minioms.verifier;

import com.minioms.common.DiffType;
import com.minioms.common.RowDiff;

import java.util.List;

/** 校验汇总。 */
public record VerifySummary(int chunks, int equalChunks, int mismatchedChunks, List<RowDiff> diffs) {

    public boolean allEqual() {
        return diffs.isEmpty();
    }
}
