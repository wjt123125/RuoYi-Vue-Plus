package org.dromara.databus.connector.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcUrlBuilder} 的超时注入与参数过滤单元测试（方案 §3.5 / §9.1 P0）。
 *
 * <p><b>为什么单独一个测试类</b>：{@code socketTimeout} 曾被和 {@code connectTimeout} 注成同值，
 * 在 MySQL / MariaDB 上表现为「任何跑超过 3 秒的查询在 socket 层直接断连」，而
 * {@code queryTimeoutSec=30} 与 {@code Statement.setQueryTimeout} 那条正常出口永远够不着。
 * pg 靶子测不出来（pg 只有 {@code connectTimeout}，单位秒且只管建连），是靠 MySQL 靶子才暴露的。
 * 这里把「建连超时 ≠ 读写超时」钉死，防再回归。
 *
 * <p>纯字符串拼装，不连库、不起容器（§5.4：连接器模块可单独 {@code mvn test}）。
 *
 * @author databus
 */
@Tag("dev")
class JdbcUrlBuilderTest {

    /** 建池路径的读写超时（默认 queryTimeoutSec=30 → 30000 + 5000 余量） */
    private static final int POOL_READ_TIMEOUT_MS = 35000;

    private static JdbcConnectionCfg cfg(JdbcDbType type, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("host", "localhost");
        m.put("port", type.getDefaultPort());
        m.put("database", "databus_jdbc_demo");
        m.put("username", "databus_demo");
        m.put("password", "pw");
        if (extra != null) {
            m.putAll(extra);
        }
        return JdbcConnectionCfg.from(type, m);
    }

    @Test
    void mysql建池路径下读写超时不再等于建连超时() {
        JdbcConnectionCfg cfg = cfg(JdbcDbType.MYSQL, null);
        assertEquals(3000, cfg.connectTimeoutMs());
        assertEquals(POOL_READ_TIMEOUT_MS, cfg.readTimeoutMs());

        String url = JdbcUrlBuilder.buildUrl(JdbcDbType.MYSQL, cfg, cfg.connectTimeoutMs(), cfg.readTimeoutMs());
        assertTrue(url.contains("connectTimeout=3000"), "建连超时仍是 3000：" + url);
        assertTrue(url.contains("socketTimeout=" + POOL_READ_TIMEOUT_MS), "读写超时必须罩住 queryTimeoutSec：" + url);
        assertFalse(url.contains("socketTimeout=3000"), "socketTimeout 绝不能落到 3000：" + url);
    }

    @Test
    void mysql探活路径下两项超时都是三千毫秒() {
        JdbcConnectionCfg cfg = cfg(JdbcDbType.MYSQL, null).forProbe();
        String url = JdbcUrlBuilder.buildUrl(JdbcDbType.MYSQL, cfg,
            JdbcConnectionCfg.PROBE_TIMEOUT_MS, JdbcConnectionCfg.PROBE_TIMEOUT_MS);
        assertTrue(url.contains("connectTimeout=3000"), url);
        assertTrue(url.contains("socketTimeout=3000"), "探活必须保持 3 秒收口（SSRF 慢速探测）：" + url);
        assertFalse(url.contains("socketTimeout=" + POOL_READ_TIMEOUT_MS), url);
    }

    @Test
    void properties与url注入同一套超时值() {
        JdbcConnectionCfg cfg = cfg(JdbcDbType.MYSQL, null);
        Properties props = JdbcUrlBuilder.buildProps(JdbcDbType.MYSQL, cfg, cfg.connectTimeoutMs(), cfg.readTimeoutMs());
        assertEquals("3000", props.getProperty("connectTimeout"));
        assertEquals(String.valueOf(POOL_READ_TIMEOUT_MS), props.getProperty("socketTimeout"));
        // 凭证只在 Properties，永不进 URL（§3.5 / §9.1 P0）
        assertEquals("databus_demo", props.getProperty("user"));
        assertEquals("pw", props.getProperty("password"));
    }

    @Test
    void 读写超时跟随queryTimeoutSec放大() {
        JdbcConnectionCfg cfg = cfg(JdbcDbType.MYSQL, Map.<String, Object>of("queryTimeoutSec", 120));
        assertEquals(125000, cfg.readTimeoutMs());
        String url = JdbcUrlBuilder.buildUrl(JdbcDbType.MYSQL, cfg, cfg.connectTimeoutMs(), cfg.readTimeoutMs());
        assertTrue(url.contains("socketTimeout=125000"), url);
        assertTrue(url.contains("connectTimeout=3000"), url);
    }

    @Test
    void 读写超时不小于建连超时() {
        // queryTimeoutSec 最小 1 → 1000 + 5000 = 6000；建连超时被显式抬到 20000 时应取大者
        JdbcConnectionCfg cfg = cfg(JdbcDbType.MYSQL, Map.<String, Object>of("queryTimeoutSec", 1, "connectTimeoutMs", 20000));
        assertEquals(20000, cfg.readTimeoutMs());
    }

    @Test
    void postgres不受影响仍是单键秒级向上取整() {
        JdbcConnectionCfg cfg = cfg(JdbcDbType.POSTGRESQL, Map.<String, Object>of("connectTimeoutMs", 1500));
        assertEquals(1500, cfg.connectTimeoutMs());

        String url = JdbcUrlBuilder.buildUrl(JdbcDbType.POSTGRESQL, cfg, cfg.connectTimeoutMs(), cfg.readTimeoutMs());
        // 秒级参数向上取整且最小 1：1500ms 只能落 2，落 0 在 pg 表示「不限时」会废掉探活收口
        assertTrue(url.contains("connectTimeout=2"), url);
        assertFalse(url.contains("socketTimeout"), "pg 没有读写超时键：" + url);
    }

    @Test
    void 用户自填的读写超时键仍被丢弃且不报错() {
        Map<String, String> driverProps = new LinkedHashMap<>();
        driverProps.put("socketTimeout", "1");
        driverProps.put("connectTimeout", "1");
        JdbcConnectionCfg cfg = cfg(JdbcDbType.MYSQL, Map.<String, Object>of("driverProps", driverProps));

        // 平台专属键是「丢弃 + warn」不是「禁用报错」：一刀切禁掉等于把 §3.5 的实现载体也禁了
        Properties props = assertDoesNotThrow(
            () -> JdbcUrlBuilder.buildProps(JdbcDbType.MYSQL, cfg, cfg.connectTimeoutMs(), cfg.readTimeoutMs()));
        assertEquals("3000", props.getProperty("connectTimeout"), "用户填的值必须被平台值覆盖");
        assertEquals(String.valueOf(POOL_READ_TIMEOUT_MS), props.getProperty("socketTimeout"));
    }
}
