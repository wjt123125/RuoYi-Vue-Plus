package org.dromara.databus.connector.jdbc.postgresql;

import org.dromara.databus.connector.jdbc.AbstractJdbcConnector;
import org.dromara.databus.connector.jdbc.JdbcDbType;

/**
 * PostgreSQL JDBC 连接器（type={@code jdbc-postgresql}，方案 §十一 阶段 B 第 6 步）。
 *
 * <p>一库一包（§2.4）：本类只是「选定了哪个库」的声明，全部行为在
 * {@link AbstractJdbcConnector}。type / label / 驱动类名 / URL 模板 / 默认端口 / 探活 SQL /
 * 超时键 / URL 参数黑名单全部来自 {@link JdbcDbType#POSTGRESQL} 枚举——那 7 个库的白名单
 * 是唯一事实源（§9.1 P0：平台只认这 7 个 type，不允许自填 driverClass）。
 *
 * <p>新增一个库 = 复制本类改一个枚举常量 + 复制 pom 改驱动坐标，平台代码零改动。
 *
 * <p>装载方式：由 {@code PluginManager} 反射实例化（<b>不是</b> Spring {@code @Component}），
 * 或 dev profile 下由 {@code DevConnectorLoader} 从 {@code target/classes} 装载（§五）。
 *
 * @author databus
 */
public class PostgresJdbcConnector extends AbstractJdbcConnector {

    @Override
    public JdbcDbType dbType() {
        return JdbcDbType.POSTGRESQL;
    }
}
