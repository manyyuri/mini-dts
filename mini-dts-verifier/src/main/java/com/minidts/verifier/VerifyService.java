package com.minidts.verifier;

import com.minidts.common.Connections;
import com.minidts.common.DbEndpoint;
import com.minidts.common.DtsException;
import com.minidts.common.DiffType;
import com.minidts.common.JdbcPkBoundaryScanner;
import com.minidts.common.PkCodec;
import com.minidts.common.PkResolver;
import com.minidts.common.RowCodec;
import com.minidts.common.RowDiff;
import com.minidts.common.VerifyReportStore;
import com.minidts.common.Values;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRC chunk 对账。
 *
 * 红线 R6：分片与全量迁移共用 JdbcPkBoundaryScanner + 同一 chunkSize。
 * 红线 R1：两端行编码共用 RowCodec。
 * 漂移防护：chunk CRC 不一致时【重读两端】再逐行比对，过滤掉“校验瞬间源在变”造成的假差异。
 */
public class VerifyService {

    private final DbEndpoint source;
    private final DbEndpoint target;
    private final String schema;
    private final String table;
    private final int chunkSize;
    private final VerifyReportStore reportStore;

    public VerifyService(DbEndpoint source, DbEndpoint target, String schema, String table,
                         int chunkSize, VerifyReportStore reportStore) {
        this.source = source;
        this.target = target;
        this.schema = schema;
        this.table = table;
        this.chunkSize = Math.max(1, chunkSize);
        this.reportStore = reportStore;
    }

    public VerifySummary verify(String taskId) {
        reportStore.clearTask(taskId);
        try (Connection src = Connections.open(source); Connection tgt = Connections.open(target)) {
            List<String> pks = PkResolver.resolvePkColumns(src, schema, table);
            if (pks.size() > 1) throw new DtsException("复合主键暂不支持校验: " + pks);
            String pk = pks.get(0);

            List<Object> uppers = JdbcPkBoundaryScanner.scanUpperBounds(src, schema, table, pk, chunkSize);
            List<RowDiff> allDiffs = new ArrayList<>();
            int equal = 0;
            for (int i = 0; i < uppers.size(); i++) {
                Object lower = i == 0 ? null : uppers.get(i - 1);
                Object upper = uppers.get(i);
                ChunkCrc a = chunkCrc(src, pk, lower, upper);
                ChunkCrc b = chunkCrc(tgt, pk, lower, upper);
                boolean chunkEqual = a.count == b.count && a.crc == b.crc;
                List<RowDiff> diffs = List.of();
                boolean realMismatch = false;
                if (!chunkEqual) {
                    // CRC 不一致 → 重读两端逐行 diff；重读后差异消失 = 校验瞬间漂移，不算真差异
                    diffs = diffChunk(src, tgt, pk, lower, upper);
                    realMismatch = !diffs.isEmpty() && diffStillReal(src, tgt, pk, lower, upper, diffs);
                    if (!realMismatch) diffs = List.of();
                }
                reportStore.saveChunkResult(taskId, i, PkCodec.encode(upper),
                        chunkEqual || !realMismatch, a.crc, b.crc, diffs);
                if (realMismatch) allDiffs.addAll(diffs);
                else equal++;
            }
            allDiffs.addAll(targetOnlyBeyond(src, tgt, pk, uppers.isEmpty() ? null : uppers.get(uppers.size() - 1)));
            return new VerifySummary(uppers.size(), equal, uppers.size() - equal, allDiffs);
        } catch (SQLException e) {
            throw new DtsException("校验失败: " + e.getMessage(), e);
        }
    }

    record ChunkCrc(long crc, int count) {}

    private ChunkCrc chunkCrc(Connection conn, String pk, Object lower, Object upper) throws SQLException {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (EncodedRow row : readChunk(conn, pk, lower, upper)) {
            if (count > 0) sb.append(RowCodec.COL_SEP);
            sb.append(row.encoded);
            count++;
        }
        return new ChunkCrc(RowCodec.crc32(sb.toString()), count);
    }

    record EncodedRow(String pk, String encoded) {}

    private List<EncodedRow> readChunk(Connection conn, String pk, Object lower, Object upper) throws SQLException {
        String q = JdbcPkBoundaryScanner.qualify(schema, table);
        String pkq = JdbcPkBoundaryScanner.quote(pk);
        String sql = lower == null
                ? "SELECT * FROM " + q + " WHERE " + pkq + " <= ? ORDER BY " + pkq
                : "SELECT * FROM " + q + " WHERE " + pkq + " > ? AND " + pkq + " <= ? ORDER BY " + pkq;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (lower == null) {
                ps.setObject(1, upper);
            } else {
                ps.setObject(1, lower);
                ps.setObject(2, upper);
            }
            ps.setFetchSize(chunkSize);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> cols = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
                int pkIdx = cols.indexOf(pk) >= 0 ? cols.indexOf(pk) : cols.indexOf(pk.toLowerCase());
                List<EncodedRow> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> vals = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) vals.add(Values.normalize(rs.getObject(i)));
                    rows.add(new EncodedRow(PkCodec.encode(vals.get(pkIdx)), RowCodec.encode(cols, vals)));
                }
                return rows;
            }
        }
    }

    private List<RowDiff> diffChunk(Connection src, Connection tgt, String pk, Object lower, Object upper)
            throws SQLException {
        Map<String, String> s = toMap(readChunk(src, pk, lower, upper));
        Map<String, String> t = toMap(readChunk(tgt, pk, lower, upper));
        List<RowDiff> diffs = new ArrayList<>();
        for (Map.Entry<String, String> e : s.entrySet()) {
            String tv = t.get(e.getKey());
            if (tv == null) diffs.add(new RowDiff(e.getKey(), DiffType.MISSING, e.getValue(), null));
            else if (!tv.equals(e.getValue())) diffs.add(new RowDiff(e.getKey(), DiffType.VALUE_DIFF, e.getValue(), tv));
        }
        for (Map.Entry<String, String> e : t.entrySet()) {
            if (!s.containsKey(e.getKey())) diffs.add(new RowDiff(e.getKey(), DiffType.TARGET_ONLY, null, e.getValue()));
        }
        return diffs;
    }

    /** 二次重读：diffs 仍在 → 真差异；消失 → 校验瞬间源在变（漂移），跳过上报。 */
    private boolean diffStillReal(Connection src, Connection tgt, String pk, Object lower, Object upper,
                                  List<RowDiff> diffs) throws SQLException {
        if (diffs.isEmpty()) return true;
        List<RowDiff> recheck = diffChunk(src, tgt, pk, lower, upper);
        return !recheck.isEmpty();
    }

    /** 目标端超出源端最大主键的多余行（不在任何源 chunk 内，单独兜底）。 */
    private List<RowDiff> targetOnlyBeyond(Connection src, Connection tgt, String pk, Object sourceMax) {
        if (sourceMax == null) return List.of();
        String q = JdbcPkBoundaryScanner.qualify(schema, table);
        String pkq = JdbcPkBoundaryScanner.quote(pk);
        try (PreparedStatement ps = tgt.prepareStatement(
                "SELECT * FROM " + q + " WHERE " + pkq + " > ? ORDER BY " + pkq)) {
            ps.setObject(1, sourceMax);
            try (ResultSet rs = ps.executeQuery()) {
                List<RowDiff> diffs = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    List<Object> vals = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) vals.add(Values.normalize(rs.getObject(i)));
                    diffs.add(new RowDiff(PkCodec.encode(vals.get(0)), DiffType.TARGET_ONLY, null,
                            RowCodec.encode(columnNames(md), vals)));
                }
                return diffs;
            }
        } catch (SQLException e) {
            throw new DtsException("扫描目标端多余行失败: " + e.getMessage(), e);
        }
    }

    private static List<String> columnNames(ResultSetMetaData md) throws SQLException {
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
        return cols;
    }

    private static Map<String, String> toMap(List<EncodedRow> rows) {
        Map<String, String> map = new LinkedHashMap<>();
        for (EncodedRow r : rows) map.put(r.pk(), r.encoded());
        return map;
    }
}
