package com.minidts.connector;

import com.minidts.common.ColumnType;

import java.util.List;

/**
 * 类型映射端口（与 {@link SqlDialect} 分开，因为它们是两件事）：
 * <ul>
 *   <li>{@code SqlDialect} 决定「语句长什么样」—— 运行时、每写一批都要用；</li>
 *   <li>{@code TypeMapper} 决定「这一列能不能搬过去」—— 建任务时用一次。</li>
 * </ul>
 * OMS 里前者属于 Connector、后者属于「结构迁移 / 迁移评估」，本类就是后者的 mini 版。
 */
public interface TypeMapper {

    String name();

    /** 建议的目标类型（无问题时给出）。 */
    String targetType(ColumnType source);

    /** 不兼容或必须人工确认的原因；空列表 = 可自动映射。 */
    List<String> problems(ColumnType source);
}
