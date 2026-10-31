package org.dromara.databus.connector.plugin;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorException;
import org.dromara.databus.connector.ConnectorManifest;
import org.dromara.databus.connector.ConnectorRegistry;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 连接器包管理器（万物皆包，方案 §4.3 / §4.2.1 / §1.4.3 / §6.1）。
 *
 * <p>平台运行期的唯一装载入口：拿到一个已落盘的连接器包目录 →
 * 解压 {@code lib/} 下的 jar → 建 child-first {@link PluginClassLoader} →
 * 按 manifest 的 {@code mainClass} 反射实例化 → 校验 implements {@link Connector} 且
 * {@code getType()} 与 manifest 一致 → 注册进 {@link ConnectorRegistry}。
 *
 * <p>卸载只做方案 §4.3 的第 3、4 步（{@code loader.close()} + {@code registry.unregister}）：
 * 第 1、2 步（关池、注销驱动）由连接器自己的 {@code onShutdown()} 在<b>包内</b>执行——
 * 那些 API 是 {@code @CallerSensitive} 的，平台侧调会静默空转甚至抛 {@code SecurityException}。
 * 本类调用 {@code connector.onShutdown()}，方法体在连接器实现里执行，caller 判定自然通过。
 *
 * <p>广播（§6.1 挂钩落点）：连接增删改 → {@link #broadcastConnectionChanged}；def 行停用 →
 * {@link #broadcastSuspend}；应用关闭 → {@link #shutdownAll()}。统一落到 {@link Connector}
 * 的三个 default 方法，Service 侧只认接口、不认具体连接器包。
 *
 * @author databus
 */
@Slf4j
@Component
public class PluginManager {

    /** 包内清单文件名（zip 根级），与上传落盘 {@code DatabusConnectorServiceImpl} 保持一致 */
    private static final String PACKAGE_FILE_NAME = "connector-package.zip";

    /** zip 内连接器实现与驱动所在前缀 */
    private static final String LIB_PREFIX = "lib/";

    /** JDBC 驱动的 SPI 声明资源名：驱动 jar 都带，是「包里有哪些驱动」的无连接事实源（§4.2.1） */
    private static final String DRIVER_SPI_RESOURCE = "META-INF/services/java.sql.Driver";

    /** 健康态：实例在跑，且它要的驱动确实由包内提供 */
    public static final String HEALTH_OK = "OK";

    /** 健康态：驱动缺失或落到了平台那份（包内 lib/ 缺驱动 jar）——能看见但连不上 */
    public static final String HEALTH_MISSING_DRIVER = "MISSING_DRIVER";

    /** 健康态：运行中没有这个连接器的实例 */
    public static final String HEALTH_NOT_LOADED = "NOT_LOADED";

    private final ConnectorRegistry registry;

    /**
     * type → 最近一次装载失败的原因（人话）。装载成功即清除，卸载也清除，重启后自然为空。
     *
     * <p>为什么要这张表：def 行只记「该不该装」（status），不记「装的时候为什么没装上」。
     * 装载失败发生在启动回装或点重载的那一刻，异常抛完就没了；列表页要说出真实原因，
     * 只能在这里留一条内存记录。不落库——它是运行态事实，和 {@code ScriptRuntimeVo} 一个口径。
     */
    private final Map<String, String> loadFailures = new ConcurrentHashMap<>();

    public PluginManager(ConnectorRegistry registry) {
        this.registry = registry;
    }

    /**
     * 装载一个连接器包：解压 → 建 loader → 反射实例化 → 校验驱动 → 注册。
     *
     * <p>失败时把原因记进 {@link #loadFailures}，供列表页现算健康态时说出真实原因；
     * 成功则清掉上一次留下的记录。异常照常抛出，调用方（上传预检 / 启动回装 / 重载）自己决定怎么处理。
     *
     * @param type       连接器类型（与 manifest 一致，作注册键）
     * @param packageDir 包目录（含 {@code connector-package.zip}；解压产物也落此目录）
     * @param manifest   已校验的 manifest（装载前由上游解析/校验）
     * @return 已注册的 Connector 实例
     */
    public Connector load(String type, Path packageDir, ConnectorManifest manifest) {
        try {
            Connector connector = doLoad(type, packageDir, manifest);
            loadFailures.remove(type);
            return connector;
        } catch (ConnectorException e) {
            loadFailures.put(type, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            loadFailures.put(type, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
    }

    private Connector doLoad(String type, Path packageDir, ConnectorManifest manifest) {
        if (manifest.getMainClass() == null || manifest.getMainClass().isBlank()) {
            throw new ConnectorException("LOAD_FAILED",
                "manifest.json 缺少 mainClass（engine=jvm 反射实例化必要字段）：" + type);
        }
        Path zip = packageDir.resolve(PACKAGE_FILE_NAME);
        if (!Files.isRegularFile(zip)) {
            throw new ConnectorException("LOAD_FAILED", "连接器包不存在：" + zip);
        }
        List<Path> jars = extract(zip, packageDir);
        URL[] urls = jars.stream().map(p -> p.toUri()).map(uri -> {
            try {
                return uri.toURL();
            } catch (Exception e) {
                throw new ConnectorException("LOAD_FAILED", "jar 路径非法：" + uri, e);
            }
        }).toArray(URL[]::new);

        PluginClassLoader loader = new PluginClassLoader(urls, PluginManager.class.getClassLoader());
        try {
            Class<?> clazz = Class.forName(manifest.getMainClass(), true, loader);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Connector connector)) {
                throw new ConnectorException("LOAD_FAILED",
                    "mainClass " + manifest.getMainClass() + " 未实现 " + Connector.class.getName());
            }
            if (!type.equals(connector.getType())) {
                throw new ConnectorException("LOAD_FAILED",
                    "manifest.type=" + type + " 与实现 getType()=" + connector.getType() + " 不一致");
            }
            // 校验放在实例化之后：驱动名要问连接器本人要（requiredDriverClass），
            // 校验不过就不注册，Registry 里跑着的旧实例原地不动
            verifyRequiredDriver(type, loader, connector);
            registry.register(type, manifest.getVersion(), connector, loader);
            log.info("装载连接器 type={} version={} mainClass={} libs={}",
                type, manifest.getVersion(), manifest.getMainClass(), jars.size());
            return connector;
        } catch (ConnectorException e) {
            closeQuietly(loader);
            throw e;
        } catch (ReflectiveOperationException | LinkageError e) {
            closeQuietly(loader);
            throw new ConnectorException("LOAD_FAILED",
                "实例化 mainClass=" + manifest.getMainClass() + " 失败：" + e, e);
        }
    }

    /**
     * 卸载一个连接器：onShutdown（包内自清扫）+ close loader + 注销注册表。
     */
    public void unload(String type) {
        Connector connector = registry.get(type);
        if (connector != null) {
            try {
                connector.onShutdown();
            } catch (Exception e) {
                log.warn("连接器 type={} onShutdown() 异常（继续 close/注销）：{}", type, e.toString());
            }
        }
        ClassLoader loader = registry.loaderOf(type);
        if (loader instanceof PluginClassLoader pluginClassLoader) {
            closeQuietly(pluginClassLoader);
        }
        registry.unregister(type);
        loadFailures.remove(type);
        log.info("卸载连接器 type={}", type);
    }

    /**
     * 广播连接变更（连接增删改后调用）。有池的连接器借此关闭单个连接的池。
     */
    public void broadcastConnectionChanged(String connectionId) {
        for (Connector connector : registry.all().values()) {
            try {
                connector.onConnectionChanged(connectionId);
            } catch (Exception e) {
                log.warn("连接器 type={} onConnectionChanged({}) 异常：{}",
                    connector.getType(), connectionId, e.toString());
            }
        }
    }

    /**
     * 广播停用（def 行置 DISABLED 时调用）。有池的连接器关闭本类型全部池。
     */
    public void broadcastSuspend(String type) {
        Connector connector = registry.get(type);
        if (connector != null) {
            try {
                connector.onSuspend();
            } catch (Exception e) {
                log.warn("连接器 type={} onSuspend() 异常：{}", type, e.toString());
            }
        }
    }

    /**
     * 运行时信息（§4.2.1）：当前实际加载器与生效驱动清单。
     */
    public Map<String, Object> runtimeInfo(String type) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", type);
        String version = registry.versionOf(type);
        info.put("classLoader", version == null ? null : "PluginClassLoader[" + type + "/" + version + "]");
        info.put("drivers", collectDrivers(type));
        return info;
    }

    /**
     * 装载校验：连接器自己声明需要的驱动，必须由包内提供，不许落到平台自带的那份。
     *
     * <p>为什么要拦：{@code Class.forName(name, false, loader)} 在 child-first 下找不到类就委派父级，
     * 包漏打驱动 jar 时平台那份会被静默顶上——版本随平台走、卸载清扫也清不掉它，
     * 故障要等到建连失败才暴露。这里在装载环节直接判死，让「包缺驱动」变成明确的装载失败。
     *
     * <p>为什么问连接器要驱动名，而不扫包里的 {@code META-INF/services/java.sql.Driver}：
     * 那份 SPI 声明在驱动 jar 内部，{@code lib/} 下没有驱动 jar 时它跟着一起消失，扫出来是空集——
     * 「没有需求就没有缺口」，校验形同放行。这正是「明明没有 jar 包，重载还提示成功」的成因。
     *
     * <p>不需要驱动的连接器（{@link Connector#requiredDriverClass()} 返回空）直接通过。
     * dev 目录装载同此口径。
     */
    void verifyRequiredDriver(String type, PluginClassLoader loader, Connector connector) {
        String required = connector.requiredDriverClass();
        if (required == null || required.isBlank()) {
            return;
        }
        String driverClass = required.trim();
        Class<?> clazz;
        try {
            clazz = Class.forName(driverClass, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new ConnectorException("LOAD_FAILED",
                "连接器 " + type + " 需要驱动 " + driverClass + "，包内找不到这个类；"
                    + "把对应的驱动 jar 放进包的 lib/ 目录后重新装载", e);
        }
        if (clazz.getClassLoader() != loader) {
            throw new ConnectorException("LOAD_FAILED",
                "连接器 " + type + " 需要驱动 " + driverClass + "，包内没有，实际用的是平台自带的那份；"
                    + "把对应的驱动 jar 放进包的 lib/ 目录后重新装载");
        }
    }

    /**
     * 连接器运行态健康（§4.2.1）：内存现算，不落库，与组件的 {@code ScriptRuntimeVo} 同一口径。
     *
     * <p>判据按新旧顺序分三支：
     * <ul>
     *   <li>{@link #loadFailures} 有记录 → 照记录说（文案提到驱动归 {@link #HEALTH_MISSING_DRIVER}，
     *       其余归 {@link #HEALTH_NOT_LOADED}）。这条<b>排在最前</b>：装载失败时上一版实例是故意留下的
     *       （{@code DevConnectorLoader} 校验不过就不卸旧的，保证「改坏了也能继续跑上一版」），
     *       若先看 Registry 有没有实例，这次失败会被那个旧实例永久盖住——界面上照样一片正常，
     *       而失败记录成功装载即清，所以它存在就说明最近一次装载确实没成。</li>
     *   <li>Registry 里有实例 → 问它要驱动名，看这个类是不是包内 loader 提供的；
     *       找不到、或找到但来自平台，都是 {@link #HEALTH_MISSING_DRIVER}。</li>
     *   <li>两边都没有 → {@link #HEALTH_NOT_LOADED}，从没装过或已被卸载。</li>
     * </ul>
     *
     * <p>def 行的 status 不参与判定：一行 DISABLED 本来就不该有实例，那种行前端按 status 显示灰色，
     * 这里只负责把运行态事实说清楚。
     */
    public ConnectorHealth healthOf(String type) {
        String failure = loadFailures.get(type);
        if (failure != null) {
            // 旧实例还在跑就补一句：否则「缺驱动」和「测试连接居然通了」摆在一起看不懂
            String reason = registry.get(type) == null ? failure
                : failure + "（上一版实例还在运行，所以连接可能暂时还能通；重启后失效）";
            return new ConnectorHealth(
                isDriverProblem(failure) ? HEALTH_MISSING_DRIVER : HEALTH_NOT_LOADED, reason);
        }
        Connector connector = registry.get(type);
        if (connector == null) {
            return new ConnectorHealth(HEALTH_NOT_LOADED,
                "运行中没有这个连接器的实例（未启用、已停用，或启动时装载没成功）");
        }
        String required = connector.requiredDriverClass();
        ClassLoader loader = registry.loaderOf(type);
        if (required == null || required.isBlank() || loader == null) {
            // 不需要驱动，或实例不是从包装载的（没有「包内 / 平台」之分可判）
            return new ConnectorHealth(HEALTH_OK, null);
        }
        String driverClass = required.trim();
        try {
            Class<?> clazz = Class.forName(driverClass, false, loader);
            if (clazz.getClassLoader() != loader) {
                return new ConnectorHealth(HEALTH_MISSING_DRIVER,
                    "驱动 " + driverClass + " 用的是平台自带的那份，包内没有；"
                        + "把驱动 jar 放进包的 lib/ 目录后重新装载");
            }
            return new ConnectorHealth(HEALTH_OK, null);
        } catch (ClassNotFoundException | LinkageError e) {
            return new ConnectorHealth(HEALTH_MISSING_DRIVER,
                "包内找不到驱动 " + driverClass + "；把驱动 jar 放进包的 lib/ 目录后重新装载");
        }
    }

    /**
     * 装载失败原因是否属驱动问题。按文案关键字判：装载侧所有驱动相关的消息都带「驱动」二字，
     * 底层 {@code NoClassDefFoundError} 也带类名里的 {@code Driver}。
     */
    private boolean isDriverProblem(String reason) {
        return reason.contains("驱动") || reason.contains("Driver");
    }

    /**
     * 记一条装载失败原因。正式包由 {@link #load} 自己记；dev 目录装载走
     * {@code DevConnectorLoader}，失败时调本方法，保证两条通道在列表页说法一致。
     */
    public void recordLoadFailure(String type, String reason) {
        loadFailures.put(type, reason);
    }

    /**
     * 清掉装载失败记录（装载成功或卸载时）。
     */
    public void clearLoadFailure(String type) {
        loadFailures.remove(type);
    }

    /**
     * 健康判定结果。
     *
     * @param health {@link #HEALTH_OK} / {@link #HEALTH_MISSING_DRIVER} / {@link #HEALTH_NOT_LOADED}
     * @param reason 不健康时的具体原因（人话，前端直显）；健康时为 {@code null}
     */
    public record ConnectorHealth(String health, String reason) {
    }

    /**
     * 采集「这个连接器包里有哪些 JDBC 驱动、生效的是哪一份」（§4.2.1）。
     *
     * <p>只枚举<b>包内自带</b>的 {@code META-INF/services/java.sql.Driver}（
     * {@link PluginClassLoader#ownResources}）：平台的 SPI 声明与这里无关，混进来会把
     * mysql/postgres 这类平台驱动算成「这个连接器的驱动」，空态也就永远不出现。
     *
     * <p>不用 {@code DriverManager.getDrivers()}：那是 {@code @CallerSensitive} +
     * {@code Reflection.getCallerClass()}，平台侧调用只返回平台自己那份，包里的驱动一条都看不见
     * （§4.3 实测表第 1 行「命中 0 条 → 循环体一次不进」）。
     *
     * <p>{@code loadedFrom} 判定＝{@code driverClass.getClassLoader() == pluginLoader}。
     * 出现 {@code PLATFORM} 只有一种成因：包声明了这个驱动类，但包里没有它，加载落到了平台。
     * 新装载的包已被 {@link #verifyRequiredDriver} 拦在门外，这一行只会出现在该校验生效之前
     * 就已装好的存量包上；前端照此给黄色告警。
     *
     * <p>{@code Class.forName(name, false, loader)} 故意不初始化：驱动静态块会往
     * {@code DriverManager} 注册自己，一个<b>诊断接口</b>不该产生这种副作用。版本因此取不到
     * {@code Driver.getMajorVersion()}，改读驱动 jar 自己的 {@code Implementation-Version}
     * （pgjdbc 42.7.11 实测有该属性）。带连接时那份 {@code DatabaseMetaData.getDriverVersion()}
     * 精确字符串由 {@code AbstractJdbcConnector.testConnection()} 的 {@code driverVersion} 键返回。
     */
    private List<Map<String, Object>> collectDrivers(String type) {
        if (!(registry.loaderOf(type) instanceof PluginClassLoader pluginLoader)) {
            return List.of();
        }
        List<Map<String, Object>> drivers = new ArrayList<>();
        for (String driverClass : ownDriverClasses(pluginLoader)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("driverClass", driverClass);
            try {
                Class<?> clazz = Class.forName(driverClass, false, pluginLoader);
                Package pkg = clazz.getPackage();
                row.put("version", pkg == null ? null : pkg.getImplementationVersion());
                row.put("loadedFrom", clazz.getClassLoader() == pluginLoader ? "PLUGIN" : "PLATFORM");
                row.put("classLoader", String.valueOf(clazz.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                // SPI 声明了但类不在包里：包打漏了驱动，正是要暴露的故障，不是要吞掉的异常
                row.put("version", null);
                row.put("loadedFrom", null);
                row.put("error", e.toString());
                log.warn("连接器 type={} 的 SPI 驱动 {} 加载失败：{}", type, driverClass, e.toString());
            }
            drivers.add(row);
        }
        return drivers;
    }

    /**
     * 包内自带的 JDBC SPI 声明（按类名去重、保留首个）。
     *
     * <p>「首个」不是随便定的：包内多份 SPI 文件写同一个类，实际生效的只有一条类，去重避免重复行。
     */
    private Set<String> ownDriverClasses(PluginClassLoader loader) {
        try {
            return readDriverNames(loader.ownResources(DRIVER_SPI_RESOURCE));
        } catch (IOException e) {
            log.warn("枚举包内 {} 失败：{}", DRIVER_SPI_RESOURCE, e.toString());
            return new LinkedHashSet<>();
        }
    }

    /**
     * 读 SPI 文件里的实现类名：去 {@code #} 注释、去空白行、保序去重。
     */
    private Set<String> readDriverNames(List<URL> urls) {
        Set<String> classes = new LinkedHashSet<>();
        for (URL url : urls) {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int comment = line.indexOf('#');
                    String name = (comment < 0 ? line : line.substring(0, comment)).trim();
                    if (!name.isEmpty()) {
                        classes.add(name);
                    }
                }
            } catch (IOException e) {
                log.warn("读取 {} 失败：{}", url, e.toString());
            }
        }
        return classes;
    }

    /**
     * 应用关闭统一广播：逐个卸载（含 onShutdown + close + 注销）。
     */
    @PreDestroy
    public void shutdownAll() {
        for (String type : new ArrayList<>(registry.all().keySet())) {
            unload(type);
        }
    }

    // ---------------------------- 私有工具 ----------------------------

    /**
     * 解压 zip 到目标目录，返回 {@code lib/} 下所有 jar 路径。
     * 逐 entry 校验路径防穿越（上传链路已校验，此处再兜一道）。
     */
    private List<Path> extract(Path zip, Path targetDir) {
        List<Path> jars = new ArrayList<>();
        Path target = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..")) {
                    throw new ConnectorException("LOAD_FAILED", "包内路径非法（不允许 ..）：" + name);
                }
                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) {
                    throw new ConnectorException("LOAD_FAILED", "包内路径越界：" + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    zis.transferTo(os);
                }
                if (name.startsWith(LIB_PREFIX) && name.endsWith(".jar")) {
                    jars.add(out);
                }
            }
        } catch (ConnectorException e) {
            throw e;
        } catch (IOException e) {
            throw new ConnectorException("LOAD_FAILED", "解压包失败（" + zip + "）：" + e.getMessage(), e);
        }
        return jars;
    }

    private void closeQuietly(PluginClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            log.warn("关闭 PluginClassLoader 失败：{}", e.toString());
        }
    }
}