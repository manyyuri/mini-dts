package com.minidts.connector;

import com.minidts.common.ColumnType;
import com.minidts.common.TypeFamily;

import java.util.List;

/** MySQL → MySQL：同族同名（除自增/无符号这类语义，同库之间不需要翻译）。 */
public final class MySqlTypeMapper implements TypeMapper {

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String targetType(ColumnType source) {
        return source.display();
    }

    @Override
    public List<String> problems(ColumnType source) {
        if (source.family() == TypeFamily.UNKNOWN) {
            return List.of("未知类型，无法自动映射：" + source.display());
        }
        return List.of();
    }
}
