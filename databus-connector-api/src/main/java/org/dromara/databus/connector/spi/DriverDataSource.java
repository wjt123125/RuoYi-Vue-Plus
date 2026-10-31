package org.dromara.databus.connector.spi;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 把 {@code java.sql.Driver} 实例包成 {@code javax.sql.DataSource} 喂给 HikariCP（方案 §4.1）。
 *
 * <p>两条硬约束：
 * <ol>
 *   <li>{@link #getConnection()} 直接 {@code driver.connect(url, props)}，<b>不走</b>
 *       {@code DriverManager.getConnection()} —— 后者经 {@code Reflection.getCallerClass()}
 *       判定调用方类加载器，平台侧调用永远拿不到插件包里的驱动。</li>
 *   <li>{@link #setLoginTimeout}/{@link #getLoginTimeout} 覆写为 no-op ——
 *       HikariCP 自己的 DriverDataSource 把它们转写成 {@code DriverManager.setLoginTimeout()}，
 *       改的是全 JVM 全局静态字段，会连平台数据源一起污染。</li>
 * </ol>
 *
 * @author databus
 */
public class DriverDataSource implements DataSource {

    private final Driver driver;
    private final String url;
    private final Properties props;

    public DriverDataSource(Driver driver, String url, Properties props) {
        this.driver = driver;
        this.url = url;
        this.props = props;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return driver.connect(url, props);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties p = new Properties();
        if (props != null) {
            p.putAll(props);
        }
        p.put("user", username);
        p.put("password", password);
        return driver.connect(url, p);
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    /** no-op：不改 DriverManager 全局 loginTimeout（方案 §3.5）。 */
    @Override
    public void setLoginTimeout(int seconds) {
    }

    /** no-op：见 {@link #setLoginTimeout}。 */
    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("DriverDataSource 不提供 java.util.logging.Logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("DriverDataSource 不是 " + iface.getName() + " 的包装器");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}