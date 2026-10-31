package org.dromara.databus.connector.jdbc;

import org.dromara.databus.connector.ConnectorException;

import java.util.Set;

/**
 * SQL 只读拦截 + 语句类型判定（方案 §9.1 P1）。
 *
 * <p>定位要说清楚：这是<b>弱防线</b>，主防线是数据库账号权限（连接管理页 UI hint 已明示
 * 「建议使用只读数据库账号」）。它能挡住的是「配了 readOnly 却误跑一条 UPDATE」这类操作事故，
 * 挡不住刻意绕过（拼注释、拼动态 SQL、存储过程里写 DML）。
 *
 * <p>另提供语句首关键字判定，供 {@code dbExecute} / {@code dbBatch} 记审计日志（§9.1 P1
 * 「SQL 首个关键字记入执行日志，便于事后定界」）。
 *
 * @author databus
 */
public final class JdbcSqlGuard {

    /** 只读连接放行的语句首关键字（各家方言的只读语句都落在这几个里） */
    private static final Set<String> READ_ONLY_STATEMENTS =
        Set.of("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN");

    private JdbcSqlGuard() {
    }

    /**
     * SQL 非空校验（组件层已校验过一次，这里是连接器自保：直接调能力接口时组件层可能被跳过）。
     *
     * @throws ConnectorException SQL 为空白
     */
    public static void requireNonBlank(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new ConnectorException("JDBC_SQL_REQUIRED", "SQL 不能为空");
        }
    }

    /**
     * 只读校验：{@code readOnly=true} 时拒绝非查询语句。
     *
     * @param sql      SQL 文本
     * @param readOnly 连接是否配了只读
     * @throws ConnectorException 只读连接上执行了写语句（错误码 {@code JDBC_READ_ONLY_VIOLATION}）
     */
    public static void checkReadOnly(String sql, boolean readOnly) {
        if (!readOnly) {
            return;
        }
        String keyword = statementType(sql);
        if (!READ_ONLY_STATEMENTS.contains(keyword)) {
            throw new ConnectorException("JDBC_READ_ONLY_VIOLATION",
                "该连接已开启只读（readOnly=true），拒绝执行 " + (keyword.isEmpty() ? "无法识别类型" : keyword)
                    + " 语句；如确需写入请关闭连接的只读开关，并使用具备写权限的数据库账号");
        }
    }

    /**
     * 取 SQL 首个关键字（大写），用于审计与只读判定。
     *
     * <p>先剥掉前导注释再取词，否则 {@code /* 说明 *}{@code / UPDATE ...} 会被判成空关键字，
     * 只读拦截当场失效。剥注释只做「开头」，不解析字符串字面量内部的 {@code --}——
     * 那是数据库的事，此处只需保证不把注释当语句。
     *
     * @return 首关键字（大写）；SQL 为空白或全注释时返回空串
     */
    public static String statementType(String sql) {
        String s = stripLeadingComments(sql);
        if (s.isEmpty()) {
            return "";
        }
        int end = 0;
        while (end < s.length() && Character.isLetter(s.charAt(end))) {
            end++;
        }
        return s.substring(0, end).toUpperCase();
    }

    /** 剥掉开头的空白、行注释（{@code --}）与块注释（{@code /* *}{@code /}），返回剩余部分 */
    private static String stripLeadingComments(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql.stripLeading();
        while (true) {
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                if (nl < 0) {
                    return "";
                }
                s = s.substring(nl + 1).stripLeading();
            } else if (s.startsWith("/*")) {
                int close = s.indexOf("*/");
                if (close < 0) {
                    return "";
                }
                s = s.substring(close + 2).stripLeading();
            } else if (s.startsWith("(")) {
                // Oracle 允许整条语句被括号包住
                s = s.substring(1).stripLeading();
            } else {
                return s;
            }
        }
    }
}
