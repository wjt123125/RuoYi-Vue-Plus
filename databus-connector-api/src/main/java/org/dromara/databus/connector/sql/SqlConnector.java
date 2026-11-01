package org.dromara.databus.connector.sql;

import org.dromara.databus.connector.Connection;

import java.util.List;
import java.util.Map;

/**
 * SQL 类连接器的能力接口（方案 §1.4.2 / §3.4）。
 *
 * <p>与通用 {@code Connector.invoke()} 逃生舱并存：SQL 是高频、同类能力有多个连接器实现
 * （jdbc-postgresql / jdbc-oracle / …），抽成能力接口换取编译期类型检查与返回值校验。
 * 脚本里 {@code (SqlConnector) ConnectorRegistry.ofType(conn.getConnectorType())} 强转成立，
 * 因为本接口与三个 record 都在 api 模块 = 父加载器只有一份。
 *
 * <p>方法名取 Spring {@code JdbcTemplate} 词汇（Java 圈事实标准），<b>但单行语义与 Spring 不同</b>，
 * 见 {@link #queryForMap} / {@link #queryForObject} 的红线说明。
 *
 * <p>新增能力接口是付出代价的决定，只在「同类能力有多个实现、平台愿意长期背书」时才做。
 *
 * @author databus
 */
public interface SqlConnector extends org.dromara.databus.connector.Connector {

    /**
     * 查多行：全部结果行（受 {@code maxRows} 与连接配置的行数上限截断）。
     *
     * @param conn 连接实例
     * @param sql  SQL 文本（PreparedStatement + ? 绑定，原样透传不做方言转换）
     * @param args 绑定参数
     * @param opts 查询选项（scalarType / maxRows / fetchSize / columnNameCase），可传 null 走连接缺省
     * @return 每行一个 {@code Map<列名, 值>}，列名已按 {@code columnNameCase} 归一；无行时为空表
     */
    List<Map<String, Object>> queryForList(Connection conn, String sql, List<Object> args, QueryOptions opts);

    /**
     * 查首行。
     *
     * <p><b>红线：LIMIT 1 语义</b>——读到首行即停，0 行返回 {@code null}，多行<b>不报错</b>。
     * 这与 Spring 同名方法不同（Spring 0 行抛 {@code EmptyResultDataAccessException}、
     * 多行抛 {@code IncorrectResultSizeDataAccessException}），行为真正等价的是 jOOQ {@code fetchAny}。
     * 需要「必须恰好一行」的语义得自己判空并核对行数，别按 Spring 的异常期望写代码。
     */
    Map<String, Object> queryForMap(Connection conn, String sql, List<Object> args, QueryOptions opts);

    /**
     * 查首行首列的标量值，按 {@code opts.scalarType()}（string / int / long / double）转换。
     *
     * <p><b>红线：LIMIT 1 语义</b>，同 {@link #queryForMap}；0 行返回 {@code null}，多行不报错
     * （等价 jOOQ {@code fetchValue}）。值为 SQL NULL 时也返回 {@code null}，不转 0 或空串。
     *
     * <p>返回 {@code Object} 而非泛型 {@code <T>}：标量类型来自画布下拉的字符串
     * （{@code scalarType}），白名单只有一处（连接器侧的 {@code JdbcResultMapper}），
     * 而它在插件类加载器里、脚本看不见。改成 Spring 那样的 {@code Class<T>} 形参就得在 api
     * 再立一份「字符串 → Class」映射表，两处白名单迟早走岔；调用方拿到值也只是写进数据空间，
     * 泛型换不到实际收益。
     */
    Object queryForObject(Connection conn, String sql, List<Object> args, QueryOptions opts);

    /**
     * 执行 INSERT / UPDATE / DELETE / DDL（JDBC {@code executeUpdate} 一类语句都不分方法，
     * Spring {@code JdbcTemplate.update()} 与 MyBatis {@code DefaultSqlSession.insert()} 同理）。
     * 单组件内包一个事务，正常返回即 commit、异常 rollback。
     *
     * @param returnGeneratedKeys 为 true 时 {@code Statement.RETURN_GENERATED_KEYS}，
     *                            自增主键回填进 {@link ExecuteResult#generatedKeys()}；
     *                            为 false 时该字段是空表
     */
    ExecuteResult update(Connection conn, String sql, List<Object> args, boolean returnGeneratedKeys);

    /**
     * 批量执行：{@code argsList} 逐组 {@code addBatch()}，每 {@code batchSize} 组 {@code executeBatch()}
     * 一次，整体包在一个事务里（任一批失败全部回滚）。
     */
    BatchResult batchUpdate(Connection conn, String sql, List<List<Object>> argsList, int batchSize);
}
