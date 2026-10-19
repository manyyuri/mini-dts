package com.minioms.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * GTID 集合的简化维护：每个 sid 记录已应用到的最大 gno，序列化为 "sid:1-max,sid2:1-max2"。
 *
 * 限制（README 已声明）：多源写入同一实例时需完整并集语义；demo 单源场景（源库一台服务器）
 * max-per-sid 即正确。
 */
public final class GtidState {

    private final TreeMap<String, Long> maxGno = new TreeMap<>();

    public static GtidState of(String gtidSet) {
        GtidState s = new GtidState();
        if (gtidSet == null || gtidSet.isBlank()) return s;
        for (String part : gtidSet.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            String[] segs = part.split(":");
            if (segs.length < 2) continue;
            String sid = segs[0];
            String[] ranges = segs[1].split("-");
            long max = Long.parseLong(ranges[ranges.length - 1]);
            s.maxGno.merge(sid, max, Math::max);
        }
        return s;
    }

    public synchronized void add(String sid, long gno) {
        maxGno.merge(sid, gno, Math::max);
    }

    public synchronized boolean isEmpty() {
        return maxGno.isEmpty();
    }

    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : maxGno.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getKey()).append(":1-").append(e.getValue());
        }
        return sb.toString();
    }

    /** 合并另一个集合（重放起点 = 一致性位点 ∪ 已应用）。 */
    public synchronized void mergeFrom(GtidState other) {
        for (Map.Entry<String, Long> e : other.maxGno.entrySet()) {
            maxGno.merge(e.getKey(), e.getValue(), Math::max);
        }
    }

    public synchronized LinkedHashMap<String, Long> snapshot() {
        return new LinkedHashMap<>(maxGno);
    }
}
