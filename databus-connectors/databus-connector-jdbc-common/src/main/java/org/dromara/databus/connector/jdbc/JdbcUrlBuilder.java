package org.dromara.databus.connector.jdbc;

import org.dromara.databus.connector.ConnectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * JDBC URL 拼装 + 参数黑名单（方案 §3.5 / §9.1 P0）。
 *
 * <p>探活与建池<b>共用同一个 builder</b>，只是传入的超时值不同（探活两项都固定 3000，建池建连用
 * {@code cfg.connectTimeoutMs()}、读写用 {@code cfg.readTimeoutMs()}）——否则「探活强制压到 3 秒」
 * 这条 P0 约束会在建池路径上失效。
 *
 * <p>三件事，顺序不能换：
 * <ol>
 *   <li><b>拆</b>：把 URL 拆成 base 与有序参数表（{@code urlOverride} 也走同一条路，用户填的
 *       URL 一样要过黑名单，不留后门）；</li>
 *   <li><b>滤</b>：平台专属超时键 → 丢弃 + warn；本库黑名单键与通用高危键 → 抛异常；
 *       {@code user} / {@code password} → 抛异常（凭证只从 credentials 注入）；</li>
 *   <li><b>拼</b>：先按超时键的语义注入平台算出的值（建连键取 {@code connectTimeoutMs}，
 *       读写键 {@code socketTimeout} 取 {@code readTimeoutMs}，见
 *       {@link JdbcDbType#isReadTimeoutKey(String)}），再按本库分隔符重组。</li>
 * </ol>
 *
 * <p>超时<b>不用</b> {@code DriverManager.setLoginTimeout()}：那是 JVM 全局静态字段，探活压到 3 秒会
 * 连平台自己的数据源重连一起改掉，且两个用户同时点「测试连接」谁后写谁赢（§3.5 三条理由）。
 * 平台超时键同时写进 URL 与 {@link Properties}：多数驱动两处都认，Oracle thin 只可靠认 Properties。
 *
 * @author databus
 */
public final class JdbcUrlBuilder {

    private static final Logger log = LoggerFactory.getLogger(JdbcUrlBuilder.class);

    /**
     * 平台专属键（§9.1）：由平台按 {@code connectTimeoutMs} 注入，用户填了一律<b>丢弃 + warn</b>，
     * 不是「禁用报错」——{@code loginTimeout} 正是 PostgreSQL / SQL Server 的超时参数名，一刀切禁掉
     * 等于把 §3.5 的实现载体也禁了，超时会静默失效。
     */
    private static final Set<String> PLATFORM_RESERVED_KEYS =
        Set.of("connectTimeout", "socketTimeout", "loginTimeout", "oracle.net.CONNECT_TIMEOUT");

    /** 凭证键：URL query 与 driverProps 两处一律拒绝（§9.1 P0） */
    private static final Set<String> CREDENTIAL_KEYS = Set.of("user", "password", "username", "pwd");

    /** 通用高危字样：含这些片段且不在放行清单内的键一律拒绝（任意类实例化面） */
    private static final List<String> GENERIC_FORBIDDEN_TOKENS =
        List.of("class", "factory", "interceptor", "handler", "plugin");

    /** 放行清单：暂无。将来某个库确实需要 {@code *Factory} 类参数时，在此按「库 + 键」精确放行 */
    private static final Set<String> GENERIC_ALLOWED_KEYS = Set.of();

    private JdbcUrlBuilder() {
    }

    /**
     * 构造连接串。
     *
     * @param type             库种（提供 URL 模板与参数分隔符）
     * @param cfg              连接配置
     * @param connectTimeoutMs 建连超时毫秒数（探活传 3000，建池传 {@code cfg.connectTimeoutMs()}）
     * @param readTimeoutMs    读写超时毫秒数，只用于 {@link JdbcDbType#isReadTimeoutKey(String)}
     *                         为真的键（当前 MySQL 系的 {@code socketTimeout}）。探活传 3000 保持
     *                         3 秒收口，建池传 {@code cfg.readTimeoutMs()}
     * @return 已过黑名单、已注入平台超时值的 JDBC URL
     * @throws ConnectorException 命中黑名单或 URL 形态非法
     */
    public static String buildUrl(JdbcDbType type, JdbcConnectionCfg cfg, int connectTimeoutMs, int readTimeoutMs) {
        String raw = isBlank(cfg.urlOverride())
            ? String.format(type.getUrlTemplate(), cfg.host(), cfg.port(), cfg.database())
            : cfg.urlOverride().trim();

        int cut = indexOfParamStart(raw);
        String base = cut < 0 ? raw : raw.substring(0, cut);
        if (base.isBlank()) {
            throw new ConnectorException("JDBC_URL_INVALID", "JDBC URL 为空");
        }
        Map<String, String> params = cut < 0 ? new LinkedHashMap<>() : parseParams(raw.substring(cut + 1));
        params = filter(type, params, "urlOverride");

        List<String> segments = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            segments.add(e.getKey() + "=" + e.getValue());
        }
        for (String key : type.getTimeoutKeys()) {
            segments.add(key + "=" + type.timeoutValue(effectiveTimeoutMs(type, key, connectTimeoutMs, readTimeoutMs)));
        }
        if (segments.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base).append(type.getFirstParamSeparator()).append(segments.get(0));
        for (int i = 1; i < segments.size(); i++) {
            sb.append(type.getParamSeparator()).append(segments.get(i));
        }
        return sb.toString();
    }

    /**
     * 构造驱动连接属性：凭证 + 过完黑名单的 {@code driverProps} + 平台超时键。
     *
     * <p>{@code user} / {@code password} 只在此处注入（来自 credentials 解密列），
     * 永不进 URL——URL 会落库、进日志、进异常栈，是凭证泄漏的主通道。
     *
     * @param connectTimeoutMs 建连超时毫秒数，语义同 {@link #buildUrl}
     * @param readTimeoutMs    读写超时毫秒数，语义同 {@link #buildUrl}
     */
    public static Properties buildProps(JdbcDbType type, JdbcConnectionCfg cfg, int connectTimeoutMs, int readTimeoutMs) {
        Properties props = new Properties();
        if (cfg.username() != null) {
            props.setProperty("user", cfg.username());
        }
        if (cfg.password() != null) {
            props.setProperty("password", cfg.password());
        }
        Map<String, String> filtered = filter(type, cfg.driverProps(), "driverProps");
        filtered.forEach(props::setProperty);
        for (String key : type.getTimeoutKeys()) {
            props.setProperty(key, type.timeoutValue(effectiveTimeoutMs(type, key, connectTimeoutMs, readTimeoutMs)));
        }
        return props;
    }

    // ---------------------------- 私有实现 ----------------------------

    /**
     * 按超时键的语义挑生效值：读写超时键（{@code socketTimeout}）取 {@code readTimeoutMs}，
     * 其余（建连超时键）取 {@code connectTimeoutMs}。
     *
     * <p>两者同值注入是 MySQL / MariaDB 上的真缺陷：默认 {@code connectTimeoutMs=3000} 会让
     * {@code socketTimeout} 也变成 3000，任何跑超过 3 秒的查询都在 socket 读超时层断连，
     * {@code queryTimeoutSec=30} 与 {@code Statement.setQueryTimeout} 那条正常出口永远够不着。
     */
    private static int effectiveTimeoutMs(JdbcDbType type, String key, int connectTimeoutMs, int readTimeoutMs) {
        return type.isReadTimeoutKey(key) ? readTimeoutMs : connectTimeoutMs;
    }

    /**
     * 参数过滤：平台专属键丢弃 + warn，黑名单键抛异常。返回新的有序表（不改入参）。
     *
     * @param origin 来源名（{@code urlOverride} / {@code driverProps}），只用于报错与 warn 定位
     */
    private static Map<String, String> filter(JdbcDbType type, Map<String, String> raw, String origin) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            if (key.isEmpty()) {
                continue;
            }
            if (isPlatformReserved(key)) {
                log.warn("[db] {} 里的 {}={} 是平台专属超时键，已丢弃（超时统一由 connectTimeoutMs 控制，防绕过探活 3 秒收口）",
                    origin, key, e.getValue());
                continue;
            }
            rejectIfForbidden(type, key, origin);
            out.put(key, e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /** 命中即抛：凭证键 / 本库黑名单 / 通用高危字样。错误信息只回键名，不回用户填的值 */
    private static void rejectIfForbidden(JdbcDbType type, String key, String origin) {
        String lower = key.toLowerCase();
        if (CREDENTIAL_KEYS.contains(lower)) {
            throw new ConnectorException("JDBC_CREDENTIAL_IN_PARAMS",
                origin + " 中不允许出现凭证键 " + key + "（用户名/密码只走连接配置的基础项，由平台加密存放）");
        }
        for (String forbidden : type.getForbiddenParams()) {
            if (forbidden.equalsIgnoreCase(key)) {
                throw new ConnectorException("JDBC_URL_PARAM_FORBIDDEN",
                    type.getDbType() + " 禁用连接参数 " + key + "（" + origin + "）：该参数可被用于读取服务器本地文件或实例化任意类");
            }
        }
        if (GENERIC_ALLOWED_KEYS.contains(lower)) {
            return;
        }
        for (String token : GENERIC_FORBIDDEN_TOKENS) {
            if (lower.contains(token)) {
                throw new ConnectorException("JDBC_URL_PARAM_FORBIDDEN",
                    "禁用连接参数 " + key + "（" + origin + "）：键名含高危字样「" + token + "」，可用于实例化任意类");
            }
        }
    }

    private static boolean isPlatformReserved(String key) {
        for (String reserved : PLATFORM_RESERVED_KEYS) {
            if (reserved.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找参数段起点：{@code ?} 与 {@code ;} 中先出现的那个。
     * SQL Server 用 {@code ;}，达梦模板自带 {@code ?schema=}，两者都要能正确拆出 base。
     */
    private static int indexOfParamStart(String url) {
        int q = url.indexOf('?');
        int s = url.indexOf(';');
        if (q < 0) {
            return s;
        }
        if (s < 0) {
            return q;
        }
        return Math.min(q, s);
    }

    /** 拆参数段：{@code &} 与 {@code ;} 都当分隔符（容忍用户在 urlOverride 里混写） */
    private static Map<String, String> parseParams(String segment) {
        Map<String, String> params = new LinkedHashMap<>();
        if (segment == null || segment.isBlank()) {
            return params;
        }
        for (String pair : segment.split("[&;]")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(pair.trim(), "");
            } else {
                params.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return params;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
