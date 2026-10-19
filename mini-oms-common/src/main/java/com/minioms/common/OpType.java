package com.minioms.common;

/** 统一操作语义：不区分 INSERT/UPDATE，全量与增量共用。 */
public enum OpType {
    UPSERT, DELETE
}
