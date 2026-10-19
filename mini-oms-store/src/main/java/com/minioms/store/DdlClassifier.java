package com.minioms.store;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * binlog QUERY 事件分类器（纯函数，可穷举单测）。
 *
 * QUERY 事件里混着三类东西，必须分清 —— 老实现一律忽略，等于把 DDL 全丢了：
 * - 事务控制语句（BEGIN / COMMIT / SAVEPOINT / SET ...）：正常忽略
 * - 结构变更（DDL）：至少记账 + 决策，不能沉默
 * - 其余（GRANT / ANALYZE / 存储过程等）：也记账，交人工
 *
 * 白名单只放「安全可自动重放」的一种：纯追加列的 {@code ALTER TABLE t ADD [COLUMN] ...}。
 * 它只加列不动既有数据；DROP / TRUNCATE / RENAME / MODIFY / CHANGE 一律人工。
 */
public final class DdlClassifier {

    public enum Kind {
        /** 事务控制 / 会话设置：忽略。 */
        IGNORE,
        /** 纯追加列：可自动在目标端重放。 */
        ADD_COLUMN,
        /** 其它结构变更：记账 + 转人工。 */
        OTHER_DDL,
        /** 非 DDL 语句（GRANT/ANALYZE/存储过程体等）：记账 + 转人工。 */
        OTHER_STATEMENT
    }

    /**
     * @param kind  分类
     * @param table 受影响表（解析不到为 null）
     * @param sql   原始语句
     */
    public record Result(Kind kind, String table, String sql) {
        public boolean needsHuman() {
            return kind == Kind.OTHER_DDL || kind == Kind.OTHER_STATEMENT;
        }

        public boolean autoApplicable() {
            return kind == Kind.ADD_COLUMN;
        }
    }

    private static final Pattern TABLE_NAME = Pattern.compile(
            "(?i)\\b(?:TABLE|INTO|UPDATE)\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?([`A-Za-z0-9_$.]+)");
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?i)^ALTER\\s+TABLE\\s+([`A-Za-z0-9_$.]+)\\s+(.+)$");
    private static final Pattern ADD_WITH_COLUMN = Pattern.compile(
            "(?i)^ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`A-Za-z0-9_$]+\\s+.+$");
    private static final Pattern ADD_PLAIN = Pattern.compile(
            "(?i)^ADD\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`A-Za-z0-9_$]+\\s+.+$");

    /** `ADD` 之后跟这些词不是加列（索引/约束/分区……），一律不算白名单。 */
    private static final Set<String> NOT_A_COLUMN = Set.of(
            "INDEX", "KEY", "UNIQUE", "PRIMARY", "FOREIGN", "FULLTEXT", "SPATIAL",
            "CONSTRAINT", "PARTITION", "CHECK", "COLUMNS");

    /** 出现即视为破坏性/不可自动重放（哪怕语句里同时有 ADD）。 */
    private static final List<String> UNSAFE_KEYWORDS =
            List.of(" DROP ", " MODIFY ", " CHANGE ", " RENAME ", " ALTER COLUMN ", " ALGORITHM=");

    private DdlClassifier() {
    }

    public static Result classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Result(Kind.IGNORE, null, sql);
        }
        String stmt = sql.trim().replaceAll("\\s+", " ");
        String upper = stmt.toUpperCase(Locale.ROOT);

        if (isTransactionControl(upper)) {
            return new Result(Kind.IGNORE, null, sql);
        }
        Matcher alter = ALTER_TABLE.matcher(stmt);
        if (alter.matches()) {
            String table = alter.group(1);
            String clauses = alter.group(2);
            boolean additive = isAdditive(clauses);
            return new Result(additive ? Kind.ADD_COLUMN : Kind.OTHER_DDL, strip(table), sql);
        }
        if (isDdlKeyword(upper)) {
            return new Result(Kind.OTHER_DDL, tableOf(stmt), sql);
        }
        return new Result(Kind.OTHER_STATEMENT, null, sql);
    }

    private static boolean isAdditive(String clauses) {
        String upper = " " + clauses.toUpperCase(Locale.ROOT) + " ";
        for (String unsafe : UNSAFE_KEYWORDS) {
            if (upper.contains(unsafe)) {
                return false;
            }
        }
        for (String clause : clauses.split(",(?![^()]*\\))")) {
            if (!isAddColumnClause(clause.trim())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAddColumnClause(String clause) {
        if (ADD_WITH_COLUMN.matcher(clause).matches()) {
            return true;
        }
        if (!ADD_PLAIN.matcher(clause).matches()) {
            return false;
        }
        String afterAdd = clause.substring(clause.toUpperCase(Locale.ROOT).indexOf("ADD") + 3).trim();
        String keyword = afterAdd.split("\\s+")[0].replace("`", "").toUpperCase(Locale.ROOT);
        return !NOT_A_COLUMN.contains(keyword);
    }

    private static boolean isTransactionControl(String upper) {
        return upper.equals("BEGIN") || upper.startsWith("BEGIN ")
                || upper.equals("COMMIT") || upper.equals("ROLLBACK") || upper.startsWith("SAVEPOINT")
                || upper.startsWith("RELEASE SAVEPOINT") || upper.startsWith("SET ")
                || upper.startsWith("XA ");
    }

    private static boolean isDdlKeyword(String upper) {
        return upper.startsWith("CREATE ") || upper.startsWith("DROP ") || upper.startsWith("TRUNCATE ")
                || upper.startsWith("RENAME ") || upper.startsWith("COMMENT ON ");
    }

    private static String tableOf(String stmt) {
        Matcher m = TABLE_NAME.matcher(stmt);
        return m.find() ? strip(m.group(1)) : null;
    }

    private static String strip(String identifier) {
        if (identifier == null) {
            return null;
        }
        String s = identifier.replace("`", "").trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
