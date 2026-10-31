package org.dromara.databus.connector.plugin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorException;
import org.dromara.databus.connector.ConnectorManifest;
import org.dromara.databus.connector.ConnectorRegistry;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发期连接器目录装载器（方案 §五，仅 dev profile 生效）。
 *
 * <p>要解决的问题（§5.1）：连接器零内置之后，改一行连接器代码要走
 * 「改代码 → mvn package → 上传 zip → 提交上架 → 另一人审核 → 启用 → 重跑链路」，
 * 一次几分钟且必须有第二个管理员参与。开发阶段不能按这个流程工作，否则实现的人会绕开它，
 * 把连接器重新写回平台代码，§1.2 的决策就落不了地。
 *
 * <p>做法（§5.2）：直接从编译输出目录装载，跳过打包、上传、审核——
 * <ol>
 *   <li>读 {@code databus.connector.dev-plugin-dir}（逗号分隔多个 {@code target/classes}）；</li>
 *   <li>建 {@link PluginClassLoader}，URL 是 {@code target/classes} 加同级 {@code target/lib/*.jar}
 *       （与 §2.4 红线二用同一目录，不另设一套；IDE 编译不生成 lib，故可选）；</li>
 *   <li>{@code Class.forName(manifest.mainClass)} 实例化，注册进 {@link ConnectorRegistry}；</li>
 *   <li>往 {@code databus_connector_def} 写一行 {@code source=DEV / status=ENABLED / version=dev}。
 *       <b>不写</b> {@code databus_market_item}——只改运行层，市场层不受影响。</li>
 * </ol>
 *
 * <p>安全边界（§5.3 四条）：
 * <ul>
 *   <li>类与 Controller 都标 {@code @Profile("dev")}：非 dev 时 Bean 不存在，
 *       不是「默认关闭但可以打开」；</li>
 *   <li>配置键只出现在 {@code application-dev.yml}，prod 与客户模板不留示例；</li>
 *   <li>路径转 {@code getCanonicalPath()} 后必须是项目根（{@code user.dir}）的下级路径，
 *       含 {@code ..}、UNC、跨盘符一律拒绝，且启动失败、打印明确错误；</li>
 *   <li>装载成功打 {@code log.warn}（打印实际 type）；reload 接口带 {@code @Log} 审计
 *       （操作人 / 时间 / type）。</li>
 * </ul>
 *
 * <p>装载时机用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}：必须晚于
 * {@code DatabusConnectorServiceImpl#reloadFromDefTable()}（后者按 def 行回装，
 * DEV 行按 source 跳过装载，改由本类从目录接管）。
 *
 * <p>dev 行不设 {@code verified}（§1.3：信任档由审批人审核时勾选，不由 source 自动推导），
 * 更新已有行时也不触碰该字段——dev 通道不能覆盖真实审核结果。
 *
 * @author databus
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevConnectorLoader {

    /**
     * dev 装载的版本号（固定值：dev 目录内容随编译变化，版本号不代表确定包内容）
     */
    private static final String DEV_VERSION = "dev";

    /**
     * 来源角标：dev 目录装载（无特权，仅展示用）
     */
    private static final String SOURCE_DEV = "DEV";

    /**
     * 运行层状态：启用（dev 装载即可用，不走审核）
     */
    private static final String STATUS_ENABLED = "ENABLED";

    /**
     * 运行层状态：已废弃（终态不可逆，dev 通道不给复活后门）
     */
    private static final String STATUS_DEPRECATED = "DEPRECATED";

    /**
     * 引擎档：jvm（目录装载只支持这一档）
     */
    private static final String ENGINE_JVM = "jvm";

    /**
     * 编译输出目录里的清单文件名（源在 {@code src/main/resources}，IDE 编译同步过来）
     */
    private static final String MANIFEST_FILE = "manifest.json";

    /**
     * 依赖 jar 目录（{@code target/lib}，maven prepare-package 生成；与 §2.4 红线二同一目录）
     */
    private static final String LIB_DIR = "lib";

    private final ConnectorRegistry connectorRegistry;

    private final PluginManager pluginManager;

    private final DatabusConnectorDefMapper defMapper;

    /**
     * Jackson 3 容器 bean（tools.jackson；携全局序列化定制）
     */
    private final ObjectMapper objectMapper;

    /**
     * dev 装载目录（逗号分隔多个 {@code target/classes}）；留空即静默 no-op
     */
    @Value("${databus.connector.dev-plugin-dir:}")
    private String devPluginDir;

    /**
     * type → 装载来源目录，供 reload 反查（只在内存，重启即丢，重启走 loadAll）
     */
    private final Map<String, Path> loadedDirs = new ConcurrentHashMap<>();

    /**
     * 启动装载：未配置即静默返回；单个目录装载失败只记 error，不阻断启动
     * （路径非法属配置错误，按 §5.3 第 3 条直接抛出使启动失败）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadAll() {
        List<String> dirs = configuredDirs();
        if (dirs.isEmpty()) {
            return;
        }
        log.warn("DEV 模式：连接器目录装载已开启，配置目录 {} 个（此模式不可用于生产）", dirs.size());
        for (String dir : dirs) {
            Path path = resolveSafeDir(dir);
            try {
                loadFrom(path);
            } catch (Exception e) {
                log.error("DEV 模式：连接器目录装载失败（{}）：{}", path, e.getMessage());
            }
        }
    }

    /**
     * 热重载：IDE 改代码 → 编译 → 调一次本方法，几秒钟完成验证（§5.2）。
     *
     * @param type 连接器类型；留空表示重载全部已配置目录
     * @return 本次重载成功的 type 列表
     */
    public List<String> reload(String type) {
        if (type == null || type.isBlank()) {
            List<String> reloaded = new ArrayList<>();
            for (String dir : configuredDirs()) {
                reloaded.add(loadFrom(resolveSafeDir(dir)));
            }
            if (reloaded.isEmpty()) {
                throw new ServiceException("未配置 databus.connector.dev-plugin-dir，无可重载的连接器目录");
            }
            return reloaded;
        }
        String key = type.trim();
        Path dir = loadedDirs.get(key);
        if (dir == null) {
            // 只给模块名不给绝对路径（整串路径糊在红条里没人读得下去），并且必须点破：
            // 这份清单是进程启动时读 yml 的快照，刚改过配置不重启，看到的永远是旧的，
            // 用户会得出「明明配了却说没配」的错误结论。
            throw new ServiceException("连接器 " + key + " 没从开发目录装载成功，没法重载。"
                + "本进程认的开发装载目录：" + configuredDirNames() + "。"
                + "如果刚改过 application-dev.yml 的 databus.connector.dev-plugin-dir，要先重启后端才会生效；"
                + "配置没问题就是该连接器上一次装载失败了，原因见连接器市场里它那一行的状态提示。");
        }
        return List.of(loadFrom(dir));
    }

    // ---------------------------- 私有实现 ----------------------------

    /**
     * 从编译输出目录装载一个连接器：校验路径与 manifest → 建 loader → 实例化 →
     * 顶掉旧实例 → 注册 → 写 def 行。
     *
     * <p>失败时关闭新建的 loader，且<b>不动</b>已在运行的旧实例（卸载发生在实例化成功之后），
     * 保证「改坏了也能继续跑上一版」。
     */
    private String loadFrom(Path classesDir) {
        if (!Files.isDirectory(classesDir)) {
            throw new ServiceException("dev 装载目录不存在（请先编译该连接器模块）：" + classesDir);
        }
        Path manifestPath = classesDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) {
            throw new ServiceException("dev 装载目录缺少 " + MANIFEST_FILE + "：" + manifestPath
                + "（manifest.json 在 src/main/resources 下，IDE 编译会同步到 target/classes，请先编译该连接器模块）");
        }
        String manifestJson;
        try {
            manifestJson = Files.readString(manifestPath);
        } catch (IOException e) {
            throw new ServiceException("读取 " + manifestPath + " 失败：" + e.getMessage());
        }
        ConnectorManifest manifest = parseManifest(manifestJson);
        manifest.validate();
        String type = manifest.getType();
        if (!ENGINE_JVM.equals(manifest.getEngine())) {
            throw new ServiceException("dev 目录装载只支持 engine=jvm（当前 " + manifest.getEngine() + "）：" + type);
        }
        // 废弃是终态：dev 通道不给复活后门（与上传链路同一口径）
        DatabusConnectorDef existing = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, type)
            .last("limit 1"));
        if (existing != null && STATUS_DEPRECATED.equals(existing.getStatus())) {
            throw new ServiceException("连接器 " + type + " 已废弃（终态不可逆），dev 通道不能复活；请先卸载再重新上传");
        }

        List<URL> urls = new ArrayList<>();
        urls.add(toUrl(classesDir));
        urls.addAll(collectLibJars(classesDir.resolveSibling(LIB_DIR)));

        PluginClassLoader loader = new PluginClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
        try {
            Class<?> clazz = Class.forName(manifest.getMainClass(), true, loader);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Connector connector)) {
                throw new ServiceException("mainClass " + manifest.getMainClass() + " 未实现 "
                    + Connector.class.getName());
            }
            if (!type.equals(connector.getType())) {
                throw new ServiceException("manifest.type=" + type + " 与实现 getType()="
                    + connector.getType() + " 不一致");
            }
            // 与正式包同一口径：驱动名问连接器本人要，必须由包内提供，缺了就是装载失败。
            // 放在卸旧实例之前，校验不过时上一版继续跑
            pluginManager.verifyRequiredDriver(type, loader, connector);
            // 实例化与驱动校验都通过才卸旧的：dev 代码顶掉已上传的正式包（本地调试口径）
            if (connectorRegistry.get(type) != null) {
                pluginManager.unload(type);
            }
            connectorRegistry.register(type, DEV_VERSION, connector, loader);
            connectorRegistry.clearInactive(type);
            connectorRegistry.clearDisabled(type);
            pluginManager.clearLoadFailure(type);
        } catch (ConnectorException e) {
            closeQuietly(loader);
            // dev 目录装载常缺 target/lib（IDE 编译不生成），这里把补法说明带上，不然只看到「包内找不到」
            String reason = e.getMessage()
                + "（dev 目录装载：先 mvn package 生成 target/lib，驱动 jar 才会在 lib/ 下）";
            pluginManager.recordLoadFailure(type, reason);
            throw new ServiceException(reason);
        } catch (ReflectiveOperationException | LinkageError e) {
            closeQuietly(loader);
            String reason = "dev 装载失败（mainClass=" + manifest.getMainClass() + " 无法实例化）：" + e;
            pluginManager.recordLoadFailure(type, reason);
            throw new ServiceException(reason);
        } catch (RuntimeException e) {
            closeQuietly(loader);
            pluginManager.recordLoadFailure(type, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }

        upsertDef(existing, type, manifest, manifestJson, classesDir);
        loadedDirs.put(type, classesDir);
        log.warn("DEV 模式：连接器 {} 已从目录装载（{}，classpath 条目 {} 个），此模式不可用于生产",
            type, classesDir, urls.size());
        return type;
    }

    /**
     * 写运行层台账：新增行不设 {@code verified}（走 DB 默认 0）与 {@code createBy}；
     * 更新已有行只刷 version/engine/manifest/package_path/status/source，
     * <b>不触碰 verified</b>（§1.3：信任档由审批人勾选，不由 source 自动推导）。
     *
     * <p>{@code package_path} 写 class 目录的父目录（{@code target}），与列定义一致
     * （该列是「zip 落盘目录」，{@code mvn package} 产出的 connector-package.zip 就在这一层）。
     * dev 装载自己不读这一列（目录来自配置项 dev-plugin-dir），写对它是为了让
     * 「package_path + connector-package.zip = zip 全路径」这条算法对全表都成立，不必按来源分支。
     */
    private void upsertDef(DatabusConnectorDef existing, String type, ConnectorManifest manifest,
                           String manifestJson, Path classesDir) {
        LocalDateTime now = LocalDateTime.now();
        String packagePath = classesDir.getParent().toString().replace('\\', '/');
        if (existing == null) {
            DatabusConnectorDef row = new DatabusConnectorDef();
            row.setType(type);
            row.setVersion(DEV_VERSION);
            row.setEngine(manifest.getEngine());
            row.setManifestSnapshot(manifestJson);
            row.setPackagePath(packagePath);
            row.setStatus(STATUS_ENABLED);
            row.setSource(SOURCE_DEV);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            defMapper.insert(row);
            return;
        }
        existing.setVersion(DEV_VERSION);
        existing.setEngine(manifest.getEngine());
        existing.setManifestSnapshot(manifestJson);
        existing.setPackagePath(packagePath);
        existing.setStatus(STATUS_ENABLED);
        existing.setSource(SOURCE_DEV);
        existing.setUpdateTime(now);
        defMapper.updateById(existing);
    }

    /**
     * 路径安全校验（§5.3 第 3 条）：拒绝 {@code ..}、UNC、跨盘符，
     * canonical 后必须是项目根（{@code user.dir}）的下级路径。
     */
    private Path resolveSafeDir(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.contains("..")) {
            throw new ServiceException("dev 装载目录不允许含 ..：" + text);
        }
        String canonical;
        String rootCanonical;
        try {
            canonical = Paths.get(text).toAbsolutePath().toFile().getCanonicalPath();
            rootCanonical = new File(System.getProperty("user.dir")).getCanonicalPath();
        } catch (IOException | RuntimeException e) {
            throw new ServiceException("dev 装载目录路径解析失败（" + text + "）：" + e.getMessage());
        }
        if (canonical.startsWith("\\\\")) {
            throw new ServiceException("dev 装载目录不允许 UNC 路径：" + text);
        }
        if (!canonical.startsWith(rootCanonical + File.separator)) {
            throw new ServiceException("dev 装载目录必须是项目根的下级路径（§5.3 路径限制）：目录 "
                + canonical + "，项目根 " + rootCanonical);
        }
        return new File(canonical).toPath();
    }

    /**
     * 配置值切目录列表（逗号分隔，忽略空白项）。
     */
    private List<String> configuredDirs() {
        List<String> dirs = new ArrayList<>();
        if (devPluginDir == null || devPluginDir.isBlank()) {
            return dirs;
        }
        for (String item : devPluginDir.split(",")) {
            if (!item.isBlank()) {
                dirs.add(item.trim());
            }
        }
        return dirs;
    }

    /**
     * 配置目录的可读名（给人看的报错用）：从 {@code .../databus-connector-jdbc-pg/target/classes}
     * 里取 {@code target} 的上一级，即 maven 模块名；取不到 target 就退化成末段目录名。
     */
    private String configuredDirNames() {
        List<String> names = new ArrayList<>();
        for (String dir : configuredDirs()) {
            String[] segs = dir.trim().replace('\\', '/').split("/");
            String name = segs[segs.length - 1];
            for (int i = 1; i < segs.length - 1; i++) {
                if ("target".equals(segs[i])) {
                    name = segs[i - 1];
                    break;
                }
            }
            names.add(name);
        }
        return names.isEmpty() ? "（未配置）" : String.join("、", names);
    }

    /**
     * 收集 {@code target/lib/*.jar}（目录不存在即返回空表——IDE 编译不生成 lib，
     * 首次需先 {@code mvn package} 把驱动放进去，§5.2 操作注意）。
     */
    private List<URL> collectLibJars(Path libDir) {
        List<URL> jars = new ArrayList<>();
        if (!Files.isDirectory(libDir)) {
            return jars;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(libDir, "*.jar")) {
            for (Path jar : stream) {
                jars.add(toUrl(jar));
            }
        } catch (IOException e) {
            throw new ServiceException("扫描 dev 依赖目录失败（" + libDir + "）：" + e.getMessage());
        }
        return jars;
    }

    /**
     * Path → URL（目录必须带尾斜杠，否则 URLClassLoader 不当目录处理）。
     */
    private URL toUrl(Path path) {
        String uri = path.toUri().toString();
        if (Files.isDirectory(path) && !uri.endsWith("/")) {
            uri = uri + "/";
        }
        try {
            return URI.create(uri).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new ServiceException("dev 装载路径非法：" + path);
        }
    }

    private ConnectorManifest parseManifest(String manifestJson) {
        try {
            return objectMapper.readValue(manifestJson, ConnectorManifest.class);
        } catch (JacksonException e) {
            throw new ServiceException("manifest.json 解析失败：" + e.getOriginalMessage());
        }
    }

    private void closeQuietly(PluginClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            log.warn("关闭 PluginClassLoader 失败：{}", e.toString());
        }
    }
}
