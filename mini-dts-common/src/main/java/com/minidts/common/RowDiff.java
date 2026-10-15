package com.minidts.common;

public record RowDiff(String pk, DiffType type, String srcRow, String tgtRow) {}
