package com.minidts.connector;

import com.minidts.common.ColumnType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 类型兼容性报告（纯函数，可单测）：给出一张「哪些列不能自动搬」的清单。
 *
 * 这是 OMS「迁移评估」的最小版本：不搬数据、只读元数据，在建任务的第一秒就把
 * 「跑到 80% 才发现类型不兼容」变成「现在就告诉你，并给出改造建议」。
 */
public final class TypeCompatibility {

    private TypeCompatibility() {
    }

    /**
     * @param columnsByTable 表 → 列类型
     * @return 每个问题一行，形如 {@code orders.amount (decimal(10,2) unsigned)：原因}
     */
    public static List<String> problems(Map<String, List<ColumnType>> columnsByTable, TypeMapper mapper) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<ColumnType>> e : columnsByTable.entrySet()) {
            for (ColumnType type : e.getValue()) {
                for (String problem : mapper.problems(type)) {
                    out.add(e.getKey() + " (" + type.display() + ")：" + problem);
                }
            }
        }
        return out;
    }

    /** 目标类型建议表（无问题时才有值）。 */
    public static Map<String, String> targetTypes(Map<String, List<ColumnType>> columnsByTable, TypeMapper mapper) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnType>> e : columnsByTable.entrySet()) {
            List<String> mapped = e.getValue().stream()
                    .map(mapper::targetType)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (mapped.size() == e.getValue().size()) {
                out.put(e.getKey(), String.join(", ", mapped));
            }
        }
        return out;
    }
}
