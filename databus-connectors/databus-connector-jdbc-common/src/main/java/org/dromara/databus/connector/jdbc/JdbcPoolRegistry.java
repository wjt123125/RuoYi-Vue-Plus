package org.dromara.databus.connector.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dromara.databus.connector.ConnectorException;
import org.dromara.databus.connector.spi.DriverDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * connectionId → HikariDataSource 缓存与生命周期（方案 §6.1）。
 *
 * <p><b>key = {@code connectionId + ":" + sha256(config ⧺ credentials)}</b>。带上 hash 是为了
 * 「用户改了密码却仍用旧池」这类最难查的故障：配置一变 hash 就变，旧池当场关闭、新池懒建。
 *
 * <p>懒建：首次业务调用才建池（连接保存时只试连不建池，§3.5「保存即验证」）。
 * 双检锁而不是裸 {@code computeIfAbsent}——工厂里要建物理连接（可能几秒），
 * 持着 map 的桶锁做 IO 会把同桶的其他连接一起卡住。
 *
 * <p>护栏（§6.1「池总数上限 50 + 物理连接总数上限 500，超限抛 {@code JDBC_POOL_LIMIT}」）：
 * 本类实例挂在 {@link AbstractJdbcConnector} 上，而连接器类随包被 child-first 加载，
 * <b>每个连接器包各有一份</b>，静态字段也各自独立。因此护栏的实际语义是
 * 「每个连接器包 ≤ 50 个池、≤ 500 条物理连接」，比方案原文的「全平台」宽
 * （7 个库都装满时理论上限 350 池 / 3500 连接）。要做成真正的全平台护栏，计数器得由平台侧持有并
 * 通过 api 契约下发，那是给 api 加接口的耦合，v1 不做；此处按包计已足够挡住「一个用户建 200 个连接
 * 把数据库连接数打满」这个原始动机——单个库被打满正是事故的形态。
 *
 * @author databus
 */
public final class JdbcPoolRegistry {

    private static final Logger log = LoggerFactory.getLogger(JdbcPoolRegistry.class);

    /** 单包池总数上限（§6.1） */
    private static final int MAX_POOLS = 50;

    /** 单包物理连接总数上限（各池 maximumPoolSize 之和，§6.1） */
    private static final int MAX_TOTAL_CONNECTIONS = 500;

    /** 空闲连接回收时间：10 分钟（§6.1） */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    /** 连接最长存活：30 分钟（§6.1，避开数据库侧 wait_timeout 静默断连） */
    private static final long MAX_LIFETIME_MS = 30 * 60 * 1000L;

    /** key → 池 */
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    /** key → 建池时的 maximumPoolSize（算护栏用，避免每次去问 Hikari） */
    private final Map<String, Integer> poolSizes = new ConcurrentHashMap<>();

    /**
     * 取池（不存在则建）。
     *
     * @param connectionId 连接实例 id
     * @param type         库种
     * @param cfg          连接配置（其 {@link JdbcConnectionCfg#fingerprint()} 参与 key）
     * @return 可用的 DataSource
     * @throws ConnectorException 超护栏（{@code JDBC_POOL_LIMIT}）、配置缺失、驱动不可用或建连失败
     */
    public DataSource dataSource(String connectionId, JdbcDbType type, JdbcConnectionCfg cfg) {
        cfg.requireConnectable();
        String key = connectionId + ":" + cfg.fingerprint();
        HikariDataSource existing = pools.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        synchronized (this) {
            existing = pools.get(key);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            if (existing != null) {
                closeQuietly(key, existing);
            }
            // 同一 connectionId 的旧配置池（hash 不同）必须关掉，否则改配置只是多占一个池
            evictStaleLocked(connectionId, key);
            checkLimitsLocked(cfg.maxPoolSize());
            HikariDataSource created = create(connectionId, type, cfg, key);
            pools.put(key, created);
            poolSizes.put(key, cfg.maxPoolSize());
            log.info("[db] 建池 connId={} db={} pool={} maxPoolSize={} totalPools={} totalConns={}",
                connectionId, type.getDbType(), created.getPoolName(), cfg.maxPoolSize(),
                pools.size(), totalConnections());
            return created;
        }
    }

    /**
     * 关某一个连接的全部池（配置变更 / 连接删除 / enabled=N，§6.1 挂钩 ①②③）。
     * 由 {@code Connector.onConnectionChanged(id)} 调用。
     */
    public void evict(String connectionId) {
        if (connectionId == null) {
            return;
        }
        String prefix = connectionId + ":";
        for (String key : new ArrayList<>(pools.keySet())) {
            if (key.startsWith(prefix)) {
                HikariDataSource ds = pools.remove(key);
                poolSizes.remove(key);
                if (ds != null) {
                    closeQuietly(key, ds);
                    log.info("[db] 关池 connId={}（连接变更广播）", connectionId);
                }
            }
        }
    }

    /**
     * 关本连接器（＝本库）的全部池（§6.1 挂钩 ④⑤⑥）。
     * 由 {@code onSuspend()} 与 {@code onShutdown()} 调用；<b>别的库不受影响</b>——
     * 每个包一份 registry，天生隔离。
     */
    public void evictAll() {
        for (String key : new ArrayList<>(pools.keySet())) {
            HikariDataSource ds = pools.remove(key);
            poolSizes.remove(key);
            if (ds != null) {
                closeQuietly(key, ds);
            }
        }
    }

    /** 当前池数（诊断用） */
    public int poolCount() {
        return pools.size();
    }

    /** 当前物理连接配额总和（诊断用） */
    public int totalConnections() {
        int total = 0;
        for (Integer size : poolSizes.values()) {
            total += size;
        }
        return total;
    }

    // ---------------------------- 私有实现 ----------------------------

    /**
     * 建 HikariCP 池。三条要点：
     * <ul>
     *   <li>{@code setDataSource(new DriverDataSource(...))}，<b>绝不</b> {@code setDriverClassName} /
     *       {@code setJdbcUrl}——那两个会让 Hikari 走 {@code DriverManager} 或它自己的
     *       DriverDataSource（后者把 setLoginTimeout 转写成 JVM 全局静态字段，§3.5 / §4.1）；</li>
     *   <li>{@code connectionTimeout} = cfg.connectTimeoutMs：拿不到连接的等待上限；</li>
     *   <li>{@code minimumIdle} 夹到不超过 {@code maximumPoolSize}，否则 Hikari 启动即抛。</li>
     * </ul>
     */
    private HikariDataSource create(String connectionId, JdbcDbType type, JdbcConnectionCfg cfg, String key) {
        Driver driver = JdbcDriverRegistry.driverFor(type);
        int timeoutMs = cfg.connectTimeoutMs();
        // 读写超时单独算：MySQL / MariaDB 的 socketTimeout 若沿用 timeoutMs（默认 3000），
        // 任何超 3 秒的查询都会在 socket 层断连，queryTimeoutSec 形同虚设。
        String url = JdbcUrlBuilder.buildUrl(type, cfg, timeoutMs, cfg.readTimeoutMs());
        Properties props = JdbcUrlBuilder.buildProps(type, cfg, timeoutMs, cfg.readTimeoutMs());

        HikariConfig hc = new HikariConfig();
        hc.setDataSource(new DriverDataSource(driver, url, props));
        hc.setMaximumPoolSize(cfg.maxPoolSize());
        hc.setMinimumIdle(Math.min(cfg.minIdle(), cfg.maxPoolSize()));
        hc.setConnectionTimeout(timeoutMs);
        hc.setIdleTimeout(IDLE_TIMEOUT_MS);
        hc.setMaxLifetime(MAX_LIFETIME_MS);
        hc.setPoolName("databus-jdbc-" + connectionId);
        hc.setReadOnly(cfg.readOnly());
        try {
            return new HikariDataSource(hc);
        } catch (RuntimeException e) {
            // 建池失败不留半死对象；异常原文可能含内网 host，只回类型 + 归一化提示（§3.5）
            log.error("[db] 建池失败 connId={} db={}", connectionId, type.getDbType(), e);
            throw new ConnectorException("JDBC_POOL_INIT_FAILED",
                "连接池初始化失败（connId=" + connectionId + "）：" + classify(e), e);
        }
    }

    /** 护栏：池数与物理连接配额两道，任一超限即拒（§6.1） */
    private void checkLimitsLocked(int maxPoolSize) {
        if (pools.size() >= MAX_POOLS) {
            throw new ConnectorException("JDBC_POOL_LIMIT",
                "连接池数量已达上限 " + MAX_POOLS + "，请删除不再使用的连接后重试");
        }
        int total = totalConnections() + maxPoolSize;
        if (total > MAX_TOTAL_CONNECTIONS) {
            throw new ConnectorException("JDBC_POOL_LIMIT",
                "物理连接总数将达 " + total + "，超过上限 " + MAX_TOTAL_CONNECTIONS
                    + "；请调小本连接的「连接池上限」或删除不再使用的连接");
        }
    }

    /** 关掉同一 connectionId 下 hash 不同的旧池（调用方已持锁） */
    private void evictStaleLocked(String connectionId, String currentKey) {
        String prefix = connectionId + ":";
        List<String> stale = new ArrayList<>();
        for (String key : pools.keySet()) {
            if (key.startsWith(prefix) && !key.equals(currentKey)) {
                stale.add(key);
            }
        }
        for (String key : stale) {
            HikariDataSource ds = pools.remove(key);
            poolSizes.remove(key);
            if (ds != null) {
                closeQuietly(key, ds);
                log.info("[db] 关旧池 connId={}（连接配置已变更）", connectionId);
            }
        }
    }

    private void closeQuietly(String key, HikariDataSource ds) {
        try {
            ds.close();
        } catch (RuntimeException e) {
            log.warn("[db] 关闭连接池失败 key={}：{}", maskKey(key), e.toString());
        }
    }

    /** key 含配置指纹，日志里只留 connectionId 段（指纹无敏感信息但也没必要打全） */
    private static String maskKey(String key) {
        int idx = key.indexOf(':');
        return idx < 0 ? key : key.substring(0, idx) + ":*";
    }

    /** 异常归一化：不回显可能含内网 host / 库名的原文（§3.5 unverified 严档） */
    private static String classify(Throwable e) {
        String msg = String.valueOf(e.getMessage());
        String lower = msg.toLowerCase();
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时";
        }
        if (lower.contains("refused") || lower.contains("connect failed") || lower.contains("unreachable")) {
            return "拒绝连接";
        }
        if (lower.contains("password") || lower.contains("authentication") || lower.contains("login failed")) {
            return "认证失败";
        }
        return "数据库不可达或参数有误（详见服务端日志）";
    }
}
