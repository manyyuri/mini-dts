package com.minidts.connector;

import com.minidts.common.RowRecord;

import java.sql.SQLException;
import java.util.List;

/**
 * 统一写入端口：全量与增量共用，不感知数据来源（红线：Connector 不得区分 Full/Store）。
 *
 * 红线 R4：advanceGtidSet 非空时，位点更新必须与数据写入在同一目标库事务内提交。
 */
public interface Sink extends AutoCloseable {

    /**
     * @param batch           一个源事务（或一个全量 chunk）的记录，保持顺序
     * @param advanceGtidSet  增量：本事务提交后的 GTID 集合；全量传 null
     */
    void writeBatch(List<RowRecord> batch, String advanceGtidSet) throws SQLException;

    @Override
    default void close() {}
}
