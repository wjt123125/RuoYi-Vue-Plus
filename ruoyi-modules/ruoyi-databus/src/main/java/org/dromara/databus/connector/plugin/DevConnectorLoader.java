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
import org.dromara.databus.enums.ConnectorDefStatusEnum;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>往 {@code databus_connector_def} 写一行 {@code source=DEV / status=ENABLED}（由
 *       {@link DevConnectorDefLedger} 承担），
 *       {@code version} 取 manifest 里的真实版本（DEV 只由来源角标表达）。
 *       <b>不写</b> {@code databus_market_item}——只改运行层，市场层不受影响。</li>
 *   <li>启动装载完按配置目录对账：配置里已移除的 type 自动删 DEV 行（配置留空即全清），
 *       保证台账与 {@code dev-plugin-dir} 严格一致，不需要人工清 SQL。</li>
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
     * 来源角标：dev 目录装载（无特权，仅展示用）
     */
    private static final String SOURCE_DEV = "DEV";

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
     * 装载目录的配置解析与路径安全校验
     */
    private final DevPluginDirResolver dirResolver;

    /**
     * DEV 台账写入与对账
     */
    private final DevConnectorDefLedger defLedger;

    /**
     * type → 装载来源目录，供 reload 反查（只在内存，重启即丢，重启走 loadAll）
     */
    private final Map<String, Path> loadedDirs = new ConcurrentHashMap<>();

    /**
     * 启动装载：按配置目录装载，装载完成后对账 DEV 台账——
     * 配置里已经没有的 type 直接删掉 def 行，不需要人工清 SQL。
     * 单个目录装载失败只记 error，不阻断启动（路径非法属配置错误，按 §5.3 第 3 条直接抛出使启动失败）。
     *
     * <p>「配置里有、但装载失败」的行不删：目录和 manifest 都在，说明这个 type 仍是开发中的对象，
     * 修好后重启即可恢复；只清那些目录已被移出配置的 type（含 {@code dev-plugin-dir} 留空＝清空全部 DEV 行）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadAll() {
        List<String> dirs = dirResolver.configuredDirs();
        Set<String> configuredTypes = new LinkedHashSet<>();
        if (dirs.isEmpty()) {
            log.warn("DEV 模式：连接器目录装载未开启（databus.connector.dev-plugin-dir 为空），"
                + "本次启动会清理残留的 DEV 台账记录");
        } else {
            log.warn("DEV 模式：连接器目录装载已开启，配置目录 {} 个（此模式不可用于生产）", dirs.size());
        }
        for (String dir : dirs) {
            Path path = dirResolver.resolveSafeDir(dir);
            // 先认 type 再装载：装载失败（驱动缺失、mainClass 报错）时也保留它的台账行，
            // 否则失败一次就把行删了，列表页连失败原因都看不到
            String manifestType = defLedger.readManifestTypeQuietly(path);
            if (manifestType != null) {
                configuredTypes.add(manifestType);
            }
            try {
                loadFrom(path);
            } catch (Exception e) {
                log.error("DEV 模式：连接器目录装载失败（{}）：{}", path, e.getMessage());
            }
        }
        defLedger.reconcileDevRows(configuredTypes);
    }

    /**
     * 热重载：IDE 改代码 → 编译 → 调一次本方法，几秒钟完成验证（§5.2）。
     *
     * @param type 连接器类型；留空表示重载全部已配置目录
     * @return 本次重载成功的 type 列表
     */
    public List<String> reload(String type) {
        if (type == null || type.isBlank()) {
            List<String> dirs = dirResolver.configuredDirs();
            if (dirs.isEmpty()) {
                throw new ServiceException("未配置 databus.connector.dev-plugin-dir，无可重载的连接器目录");
            }
            List<String> reloaded = new ArrayList<>();
            for (String dir : dirs) {
                String loadedType = loadFrom(dirResolver.resolveSafeDir(dir));
                if (loadedType != null) {
                    reloaded.add(loadedType);
                }
            }
            if (reloaded.isEmpty()) {
                throw new ServiceException("没有可重载的连接器：目录都已配置，但对应 type 均被正式包占用"
                    + "（dev 目录不接管已上传的类型，先卸载正式包再调试）");
            }
            return reloaded;
        }
        String key = type.trim();
        Path dir = loadedDirs.get(key);
        if (dir == null) {
            DatabusConnectorDef def = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
                .eq(DatabusConnectorDef::getType, key)
                .last("limit 1"));
            if (def != null && !SOURCE_DEV.equals(def.getSource())) {
                throw new ServiceException("连接器 " + key + " 已由正式包占用（source=" + def.getSource()
                    + "，version=" + def.getVersion() + "），dev 目录不接管；要本地调试请先卸载正式包");
            }
            // 只给模块名不给绝对路径（整串路径糊在红条里没人读得下去），并且必须点破：
            // 这份清单是进程启动时读 yml 的快照，刚改过配置不重启，看到的永远是旧的，
            // 用户会得出「明明配了却说没配」的错误结论。
            throw new ServiceException("连接器 " + key + " 没从开发目录装载成功，没法重载。"
                + "本进程认的开发装载目录：" + dirResolver.configuredDirNames() + "。"
                + "如果刚改过 application-dev.yml 的 databus.connector.dev-plugin-dir，要先重启后端才会生效；"
                + "配置没问题就是该连接器上一次装载失败了，原因见连接器市场里它那一行的状态提示。");
        }
        // 上次装载过、这次已被上传的包接管（source 已不是 DEV）时 loadFrom 返回 null，
        // 直接塞进 List.of 会 NPE。目录条目仍留在 loadedDirs：卸载正式包后还能从这个目录重载
        String loaded = loadFrom(dir);
        if (loaded == null) {
            throw new ServiceException("连接器 " + key + " 已由正式包占用，dev 目录不接管；要本地调试请先卸载正式包");
        }
        return List.of(loaded);
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
        // 同 type 可能已有多个版本行（决策 18），dev 目录只对应其中一个版本
        List<DatabusConnectorDef> rows = defMapper.selectList(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, type));
        // 正式包优先：DEV 行之外的行（UPLOAD 等）是审核后的正式记录，dev 目录不再抢这个 type。
        // 否则每次重启都会把用户上传的包顶掉，还会把状态改回 ENABLED、绕过审核。
        DatabusConnectorDef formal = rows.stream()
            .filter(row -> !SOURCE_DEV.equals(row.getSource()))
            .findFirst().orElse(null);
        if (formal != null) {
            log.warn("DEV 模式：跳过连接器 {}，该类型已由正式包占用（source={}，version={}）；"
                + "要本地调试请先卸载正式包", type, formal.getSource(), formal.getVersion());
            return null;
        }
        if (rows.stream().anyMatch(row -> ConnectorDefStatusEnum.DEPRECATED.getCode().equals(row.getStatus()))) {
            throw new ServiceException("连接器 " + type + " 已废弃（终态不可逆），dev 通道不能复活；请先卸载再重新上传");
        }
        DatabusConnectorDef existing = rows.stream()
            .filter(row -> SOURCE_DEV.equals(row.getSource())
                && manifest.getVersion().equals(row.getVersion()))
            .findFirst().orElse(null);

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
            // 实例化与驱动校验都通过才卸旧的：重载时用新编译的实例顶掉本类上一次装载的实例
            // （被正式包占用的 type 在上面已跳过，走不到这里）。dev 目录一个 type 只对应一个版本，
            // 版本号改过时旧版本实例也要一起卸掉
            pluginManager.unloadAll(type);
            connectorRegistry.register(type, manifest.getVersion(), connector, loader);
            connectorRegistry.clearPending(type, manifest.getVersion());
            connectorRegistry.clearDisabled(type, manifest.getVersion());
            pluginManager.clearLoadFailure(type, manifest.getVersion());
        } catch (ConnectorException e) {
            closeQuietly(loader);
            // dev 目录装载常缺 target/lib（IDE 编译不生成），这里把补法说明带上，不然只看到「包内找不到」
            String reason = e.getMessage()
                + "（dev 目录装载：先 mvn package 生成 target/lib，驱动 jar 才会在 lib/ 下）";
            pluginManager.recordLoadFailure(type, manifest.getVersion(), reason);
            throw new ServiceException(reason);
        } catch (ReflectiveOperationException | LinkageError e) {
            closeQuietly(loader);
            String reason = "dev 装载失败（mainClass=" + manifest.getMainClass() + " 无法实例化）：" + e;
            pluginManager.recordLoadFailure(type, manifest.getVersion(), reason);
            throw new ServiceException(reason);
        } catch (RuntimeException e) {
            closeQuietly(loader);
            pluginManager.recordLoadFailure(type, manifest.getVersion(),
                e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }

        defLedger.upsertDef(existing, type, manifest, manifestJson, classesDir);
        loadedDirs.put(type, classesDir);
        log.warn("DEV 模式：连接器 {} 已从目录装载（{}，classpath 条目 {} 个），此模式不可用于生产",
            type, classesDir, urls.size());
        return type;
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
