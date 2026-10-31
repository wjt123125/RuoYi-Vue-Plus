package org.dromara.databus.connector.sql;

import org.dromara.databus.connector.Connection;

import java.util.List;

/**
 * SQL 类连接器的能力接口（方案 §1.4.2）。
 *
 * <p>与通用 {@code Connector.invoke()} 逃生舱并存：SQL 是高频、同类能力有多个连接器实现
 * （jdbc-postgresql / jdbc-oracle / …），抽成能力接口换取编译期类型检查与返回值校验。
 * 脚本里 {@code (SqlConnector) ConnectorRegistry.ofType(conn.getConnectorType())} 强转成立，
 * 因为本接口与四个 record 都在 api 模块 = 父加载器只有一份。
 *
 * <p>新增能力接口是付出代价的决定，只在「同类能力有多个实现、平台愿意长期背书」时才做。
 *
 * @author databus
 */
public interface SqlConnector extends org.dromara.databus.connector.Connector {

    /**
     * SQL 查询。resultMode 决定 QueryResult 里 rows / row / value 哪个有值；
     * scalarType 只在 resultMode=scalar 时生效。
     *
     * @param conn 连接实例
     * @param sql  SQL 文本（PreparedStatement + ? 绑定，原样透传不做方言转换）
     * @param args 绑定参数
     * @param opts 查询选项（resultMode / scalarType / maxRows / fetchSize / columnNameCase）
     */
    QueryResult query(Connection conn, String sql, List<Object> args, QueryOptions opts);

    /**
     * SQL 执行（INSERT/UPDATE/DELETE/DDL）。
     *
     * @param returnGeneratedKeys 为 true 时 Statement.RETURN_GENERATED_KEYS，主键回填进结果
     */
    ExecuteResult execute(Connection conn, String sql, List<Object> args, boolean returnGeneratedKeys);

    /**
     * 批量执行：argsList 逐组 addBatch()，每 batchSize 组 executeBatch() 一次，整体包在一个事务里。
     */
    BatchResult batch(Connection conn, String sql, List<List<Object>> argsList, int batchSize);
}