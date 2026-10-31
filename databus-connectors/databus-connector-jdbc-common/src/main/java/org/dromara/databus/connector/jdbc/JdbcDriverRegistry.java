package org.dromara.databus.connector.jdbc;

import org.dromara.databus.connector.ConnectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 驱动加载与注销（方案 §4.1 / §4.3）。
 *
 * <p><b>建连接不走 {@code DriverManager.getConnection()}</b>：那是 {@code @CallerSensitive} 方法，
 * JDK 用栈上紧挨着那一帧的类加载器判定 caller，平台侧调用永远拿不到插件包里的驱动。改为
 * <pre>
 * Class&lt;?&gt; c = pluginLoader.loadClass(driverClass);      // 只加载，不触发 static{}
 * Driver real = (Driver) c.getDeclaredConstructor().newInstance();  // newInstance 才触发 static{}
 * new DriverDataSource(real, url, props)                // getConnection() 内直接 real.connect(url, props)
 * </pre>
 * 本类只做前两步并把实例缓存起来；第三步的 {@code DriverDataSource} 在 api 模块
 * （{@code connector.spi}，父加载器唯一一份），由 {@link JdbcPoolRegistry} 组装。
 *
 * <p>{@code getClassLoader()} 拿到的就是本包的 PluginClassLoader（child-first，
 * {@code java.sql.Driver} 走 PARENT_FIRST 因此接口只有一份，强转成立）。不做双版本并存，
 * 也就不需要 {@code DriverShim}：同 type 升版本＝换包＝换 loader，旧驱动随 {@link #deregisterAll()} 清掉。
 *
 * <p><b>缓存是「每包一份」而非全平台一份</b>：本类随各库包被打进 {@code lib/}，child-first 下
 * 每个 PluginClassLoader 各有一份 Class 对象，静态字段天然隔离——这正是我们要的
 * （pg 包与 mysql 包互不影响），也是 §4.3「卸载即彻底」能成立的前提。
 *
 * @author databus
 */
public final class JdbcDriverRegistry {

    private static final Logger log = LoggerFactory.getLogger(JdbcDriverRegistry.class);

    /** driverClass → 驱动实例（同一包内驱动类唯一，缓存避免每次建池都反射 newInstance） */
    private static final Map<String, Driver> CACHE = new ConcurrentHashMap<>();

    private JdbcDriverRegistry() {
    }

    /**
     * 取得本库驱动实例：从本连接器的 PluginClassLoader 加载 + {@code newInstance()}。
     *
     * @param type 库种（驱动类名取自 {@link JdbcDbType#getDriverClass()}，用户不可覆盖）
     * @throws ConnectorException 驱动类不在包内、无法实例化、或加载出来的不是 {@link Driver}
     */
    public static Driver driverFor(JdbcDbType type) {
        String driverClass = type.getDriverClass();
        Driver cached = CACHE.get(driverClass);
        if (cached != null) {
            return cached;
        }
        ClassLoader loader = JdbcDriverRegistry.class.getClassLoader();
        try {
            Class<?> clazz = loader.loadClass(driverClass);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver driver)) {
                throw new ConnectorException("JDBC_DRIVER_INVALID",
                    driverClass + " 不是 java.sql.Driver 实现（包内驱动 jar 与 api 版本不匹配？）");
            }
            CACHE.put(driverClass, driver);
            log.info("[db] 驱动已加载 {}（{} {}，loader={}）", driverClass,
                driver.getMajorVersion(), driver.getMinorVersion(), loaderName(loader));
            return driver;
        } catch (ConnectorException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            throw new ConnectorException("JDBC_DRIVER_NOT_FOUND",
                "连接器包内缺少驱动 " + driverClass + "（lib/ 下应含 " + type.getDbType() + " 驱动 jar）", e);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ConnectorException("JDBC_DRIVER_INIT_FAILED",
                "驱动 " + driverClass + " 实例化失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 注销属于本 loader 的驱动（方案 §4.3 第 1、2 步）。
     *
     * <p><b>必须在包内执行</b>：{@code DriverManager.getDrivers()} 与 {@code deregisterDriver()}
     * 都是 {@code @CallerSensitive}，JDK 用 {@code Reflection.getCallerClass()} 判定 caller。
     * 方法体在包内 → caller 是 pluginLoader → 能看见并注销插件驱动；搬到平台侧 → caller 是应用加载器 →
     * <b>一条都看不见（循环体一次都不进）</b>，硬注销还抛 {@code SecurityException}，且全程一声不响。
     * 平台侧 {@code PluginManager.unload()} 只负责<b>调用</b>本方法（经由
     * {@code AbstractJdbcConnector.onShutdown()}），之后的 {@code loader.close()} 与注销注册表才是它的活。
     *
     * <p>不做「注销全部」：平台自己的业务数据源驱动、其他连接器包的驱动都不能碰。
     */
    public static void deregisterAll() {
        ClassLoader self = JdbcDriverRegistry.class.getClassLoader();
        int removed = 0;
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() != self) {
                continue;
            }
            try {
                DriverManager.deregisterDriver(driver);
                removed++;
            } catch (SQLException e) {
                log.warn("[db] 注销驱动 {} 失败（loader 即将关闭，可能导致该驱动类无法回收）：{}",
                    driver.getClass().getName(), e.toString());
            }
        }
        CACHE.clear();
        if (removed > 0) {
            log.info("[db] 已注销本包驱动 {} 个（卸载清扫 §4.3）", removed);
        }
    }

    /** 本包已加载的驱动类名清单（诊断用；平台 runtime-info 的驱动表由平台侧自行枚举 SPI 资源得到） */
    public static java.util.Set<String> loadedDriverClasses() {
        return java.util.Set.copyOf(CACHE.keySet());
    }

    private static String loaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getSimpleName();
    }
}
