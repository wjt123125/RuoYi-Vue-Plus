package org.dromara.databus.connector.jdbc.mysql;

import org.dromara.databus.connector.jdbc.AbstractJdbcConnector;
import org.dromara.databus.connector.jdbc.JdbcDbType;

/**
 * MySQL JDBC 连接器（type={@code jdbc-mysql}，方案 §2.4「一库一包」）。
 *
 * <p>本类只是「选定了哪个库」的声明，全部行为在 {@link AbstractJdbcConnector}。
 * type / label / 驱动类名 / URL 模板 / 默认端口 / 探活 SQL / 超时键 / URL 参数黑名单
 * 全部来自 {@link JdbcDbType#MYSQL} 枚举——那 7 个库的白名单是唯一事实源
 * （§9.1 P0：平台只认这 7 个 type，不允许自填 driverClass）。
 *
 * <p>MySQL 专属注意（与 PostgreSQL 的差异，都已在 {@code JdbcDbType.MYSQL} 里落定）：
 * <ul>
 *   <li>超时有两个键：{@code connectTimeout}（建连）+ {@code socketTimeout}（读写），单位<b>毫秒</b>；</li>
 *   <li>黑名单含 {@code allowLoadLocalInfile} 一族（读服务器本地文件）与 {@code autoDeserialize} /
 *       {@code queryInterceptors} / {@code statementInterceptors}（任意类实例化）；</li>
 *   <li>{@code allowPublicKeyRetrieval} 也在黑名单内，因此 MySQL 8 默认的
 *       {@code caching_sha2_password} 账号若不走 TLS 会连不上——测试库请用
 *       {@code mysql_native_password} 账号，或在连接的 driverProps 里给 {@code sslMode=REQUIRED}。</li>
 * </ul>
 *
 * <p>装载方式：由 {@code PluginManager} 反射实例化（<b>不是</b> Spring {@code @Component}），
 * 或 dev profile 下由 {@code DevConnectorLoader} 从 {@code target/classes} 装载（§五）。
 *
 * @author databus
 */
public class MysqlJdbcConnector extends AbstractJdbcConnector {

    @Override
    public JdbcDbType dbType() {
        return JdbcDbType.MYSQL;
    }
}
