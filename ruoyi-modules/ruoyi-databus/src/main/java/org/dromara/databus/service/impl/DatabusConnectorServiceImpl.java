package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorDescriptor;
import org.dromara.databus.connector.ConnectorManifest;
import org.dromara.databus.connector.ConnectorRegistry;
import org.dromara.databus.connector.plugin.PluginManager;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.SysDatabusConnection;
import org.dromara.databus.domain.vo.DatabusConnectorDefVo;
import org.dromara.databus.enums.ConnectorDefStatusEnum;
import org.dromara.databus.enums.OwnerTypeEnum;
import org.dromara.databus.market.support.MarketStates;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.mapper.SysDatabusConnectionMapper;
import org.dromara.databus.service.IDatabusConnectorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 连接器运行层 Service 实现（万物皆包，databus-material-market.md §15.5/§15.7）。
 *
 * <p>上传链路：zip 校验（根级 manifest.json + 路径防穿越）→ manifest 必填/命名/engine
 * 校验 → type 冲突检测（同 type 同 version 报错；升版本＝热更新 def 行）→
 * 落盘 {@code plugins/{type}/{version}/{artifact}-{version}.zip} → 新类型装载预检 → 入库。新 type 默认 PENDING（待审核）：
 * 审核即信任（§15.11），enable 是治理者的显式动作。
 *
 * <p>defs 视图：连接器零内置（方案 §1.2），只查 def 行；装载后运行态从
 * {@link ConnectorRegistry} 取。运行层装载/卸载由 {@link PluginManager} 承担，
 * {@code enable}＝装载、{@code disable}＝卸载实例但保留包、{@code deprecate}＝停机并落终态
 * （保留包与 def 行，不可逆）、{@code uninstall}＝先清扫再删包。
 *
 * <p>卸载（§15.5 三档的 v1 收敛）：默认拒绝（在用连接计数 RESTRICT）→
 * force=true 强制（先 §4.3 四步清扫再删行删包，存量连接悬空自然衰减）。
 *
 * <p>包目录可配置 {@code databus.connector.package-dir}（默认相对工作目录 {@code plugins}）。
 * 应用启动时从 def 表回装：PENDING → 建未启用门禁，DISABLED → 建禁用门禁，
 * DEPRECATED → 建废弃门禁（不装载），ENABLED（jvm）→ 重新装载。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DatabusConnectorServiceImpl implements IDatabusConnectorService {

    /**
     * 引擎档：jvm（PluginClassLoader 在线装卸）
     */
    private static final String ENGINE_JVM = "jvm";

    /**
     * 来源：市场 tab 内上传
     */
    private static final String SOURCE_UPLOAD = "UPLOAD";

    /**
     * 来源：dev 目录装载（方案 §五）。启动回装跳过这类行，由 DevConnectorLoader 接管
     * （仅 dev profile 存在）。它跑的是编译输出目录里的 class，运行期不存在「包」这个实体，
     * 且 version / status / package_path 每次启动都被开发通道覆盖，因此一切针对包与启停状态的
     * 操作都按来源拒绝（见 {@link #rejectDevRow}），只留「重新加载」这一个动作。
     */
    private static final String SOURCE_DEV = "DEV";

    /**
     * 市场物料类型：连接器（反查上架者时限定 resource_type）
     */
    private static final String RESOURCE_TYPE_CONNECTOR = "CONNECTOR";

    /**
     * 市场物料状态：已上架（货架可见、可被安装）
     */
    private static final String MARKET_STATUS_ONLINE = "ONLINE";

    /**
     * 上传包体上限（50MB；manifest 声明式包通常几 KB，jvm 档 jar 为主要体量来源）
     */
    private static final long MAX_PACKAGE_BYTES = 50L * 1024 * 1024;

    /**
     * 包内清单文件名（zip 根级，必选）
     */
    private static final String MANIFEST_ENTRY = "manifest.json";

    private final DatabusConnectorDefMapper defMapper;

    private final SysDatabusConnectionMapper connectionMapper;

    /**
     * 市场物料行（只为反查上架者 owner_code：def 表自己没有作者列）
     */
    private final DatabusMarketItemMapper marketItemMapper;

    /**
     * 安装记录（判断「我的已装连接器」用：安装连接器会写一条记录）
     */
    private final DatabusInstallMapper installMapper;

    private final ConnectorRegistry connectorRegistry;

    private final PluginManager pluginManager;

    /**
     * manifest 解析用 Jackson3 mapper（构造注入容器 bean：平台为 Spring Boot4/Jackson3，
     * 容器提供的是 tools.jackson 的 ObjectMapper/JsonMapper，携全局序列化定制）
     */
    private final ObjectMapper objectMapper;

    /**
     * 包落盘根目录（默认相对工作目录 plugins；可用 databus.connector.package-dir 配置）
     */
    @Value("${databus.connector.package-dir:plugins}")
    private String packageDir;

    /**
     * 应用启动回装：PENDING → 建未启用门禁；DISABLED → 建禁用门禁；DEPRECATED → 建废弃门禁（不装载）；
     * ENABLED（jvm）→ 重新装载；source=DEV → 跳过（由 DevConnectorLoader 从目录装载，§五）。
     * 此处失败不阻断启动（与「运行时拿不到连接器是预期内错误」一致）。
     */
    @PostConstruct
    public void reloadFromDefTable() {
        try {
            for (DatabusConnectorDef def : defMapper.selectList(Wrappers.<DatabusConnectorDef>lambdaQuery())) {
                String type = def.getType();
                if (SOURCE_DEV.equals(def.getSource())) {
                    log.info("跳过 dev 装载连接器 type={}（由 DevConnectorLoader 从目录装载，仅 dev profile 生效）", type);
                } else if (ConnectorDefStatusEnum.PENDING.getCode().equals(def.getStatus())) {
                    connectorRegistry.markPending(type);
                } else if (ConnectorDefStatusEnum.DISABLED.getCode().equals(def.getStatus())) {
                    connectorRegistry.markDisabled(type);
                } else if (ConnectorDefStatusEnum.DEPRECATED.getCode().equals(def.getStatus())) {
                    connectorRegistry.markDeprecated(type);
                } else if (ConnectorDefStatusEnum.ENABLED.getCode().equals(def.getStatus()) && ENGINE_JVM.equals(def.getEngine())) {
                    try {
                        loadConnector(def);
                    } catch (Exception e) {
                        log.warn("启动装载连接器失败 type={}：{}", type, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("启动读取连接器 def 表失败（不影响启动）：{}", e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DatabusConnectorDefVo upload(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择要上传的连接器包（zip）");
        }
        if (file.getSize() > MAX_PACKAGE_BYTES) {
            throw new ServiceException("包体超过 50MB 上限");
        }
        String manifestJson = extractManifest(file);
        ConnectorManifest manifest = parseManifest(manifestJson);
        manifest.validate();

        // type 冲突检测（§15.5 上传行）：同 type 同 version 报错；升版本＝热更新。
        // DEV 行例外：它是 dev-plugin-dir 启动时临时登记的，不算正式版本占用，允许上传直接接管，
        // 否则同一个 type 会永远被本地开发目录挡住，测试用户无法验证自己的包。
        DatabusConnectorDef existing = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, manifest.getType())
            .last("limit 1"));
        boolean devRow = existing != null && SOURCE_DEV.equals(existing.getSource());
        if (existing != null && !devRow && manifest.getVersion().equals(existing.getVersion())) {
            throw new ServiceException("连接器 " + manifest.getType() + " 版本 " + manifest.getVersion()
                + " 已存在，请升级版本号后重新上传（同 type 升版本＝热更新）");
        }
        // 废弃是终态（不可逆）：不给「升版本热更新」这条复活后门，需先卸载再重新上传
        if (existing != null && ConnectorDefStatusEnum.DEPRECATED.getCode().equals(existing.getStatus())) {
            throw new ServiceException("连接器 " + manifest.getType()
                + " 已废弃（终态不可逆），不能升版本热更新；如确需继续使用，请先卸载再重新上传");
        }

        // 落盘 plugins/{type}/{version}/{artifact}-{version}.zip（type/version 已正则约束，无穿越面）
        Path dir = Paths.get(packageDir, manifest.getType(), manifest.getVersion());
        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(manifest.packageFileName()).toAbsolutePath());
        } catch (IOException e) {
            throw new ServiceException("包落盘失败（目录 " + dir + "）：" + e.getMessage());
        }
        String packagePath = dir.toString().replace('\\', '/');

        // 新类型装载预检（§10.1）：engine=jvm 上传即试装载，失败立刻报错不留到运行时；
        // 接管 DEV 行同样要预检——此时跑的还是 dev 目录的实例，正式包能不能起来必须先在这次验收
        if ((existing == null || devRow) && ENGINE_JVM.equals(manifest.getEngine())) {
            try {
                pluginManager.load(manifest.getType(), dir, manifest);
                pluginManager.unload(manifest.getType());
            } catch (Exception e) {
                deletePackageDir(packagePath);
                throw new ServiceException("连接器装载预检失败（mainClass 无法实例化）：" + e.getMessage());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        DatabusConnectorDef row;
        if (existing == null) {
            row = new DatabusConnectorDef();
            row.setType(manifest.getType());
            row.setVersion(manifest.getVersion());
            row.setEngine(manifest.getEngine());
            row.setManifestSnapshot(manifestJson);
            row.setPackagePath(packagePath);
            // 审核即信任（§15.11）：新包默认未启用，enable 是治理者显式动作
            row.setStatus(ConnectorDefStatusEnum.PENDING.getCode());
            row.setSource(SOURCE_UPLOAD);
            row.setOwnerType(OwnerTypeEnum.USER.getCode());
            row.setCreateBy(userId);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            defMapper.insert(row);
            connectorRegistry.markPending(row.getType());
        } else {
            // 升版本＝换了新内容，旧版本的审核结论不继承：清 verified、回 PENDING，
            // 并卸下旧实例、把市场物料一起下线，等作者重新提交上架、治理者重新审核启用。
            // 这条不变式是「审了才能跑」的前提——否则新包会带着上一次的 verified 直接过审。
            existing.setVersion(manifest.getVersion());
            existing.setEngine(manifest.getEngine());
            existing.setManifestSnapshot(manifestJson);
            existing.setPackagePath(packagePath);
            // 接管 DEV 行：来源改回 UPLOAD，否则查看包仍按 dev 行拒绝、下次重启 dev 目录又会把它抢回去
            existing.setSource(SOURCE_UPLOAD);
            existing.setOwnerType(OwnerTypeEnum.USER.getCode());
            existing.setVerified(Boolean.FALSE);
            existing.setStatus(ConnectorDefStatusEnum.PENDING.getCode());
            existing.setUpdateTime(now);
            // DEV 行由启动时自动登记，create_by 为空；接管后上传人即所有者，
            // 必须写入 create_by，否则 publish 的「只能上架属于自己的资源」校验过不了
            if (existing.getCreateBy() == null) {
                existing.setCreateBy(userId);
            }
            defMapper.updateById(existing);
            offlineMarketItems(existing);
            pluginManager.broadcastSuspend(existing.getType());
            pluginManager.unload(existing.getType());
            connectorRegistry.markPending(existing.getType());
            row = existing;
        }
        return toVo(row);
    }

    @Override
    public List<DatabusConnectorDefVo> listDefs() {
        // 全量台账只给治理者（能启停/卸载的人）：市场中「已上架」状态不足以看全量，
        // 上架者若拿到全量，会把别人未审核的 PENDING 包也当成货架内容（已装管理变成包台账）。
        // 非治理者只看「已启用且已上架」，没上架的包、待启用/停用/废弃一律不可见。
        boolean fullView = LoginHelper.isSuperAdmin()
            || StpUtil.hasPermission("databus:connector:edit");
        List<DatabusConnectorDef> defs = defMapper.selectList(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(!fullView, DatabusConnectorDef::getStatus, ConnectorDefStatusEnum.ENABLED.getCode())
            .orderByAsc(DatabusConnectorDef::getType));
        if (!fullView) {
            Set<Long> onlineDefIds = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                    .eq(DatabusMarketItem::getResourceType, RESOURCE_TYPE_CONNECTOR)
                    .eq(DatabusMarketItem::getStatus, MARKET_STATUS_ONLINE))
                .stream()
                .map(DatabusMarketItem::getSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            defs.removeIf(def -> !onlineDefIds.contains(def.getId()));
        }
        List<DatabusConnectorDefVo> vos = defs.stream()
            .map(this::toVo)
            .collect(Collectors.toList());
        fillOwnerCode(defs, vos);
        return vos;
    }

    /**
     * 我的已装连接器：只返回当前用户有安装记录、且处于已启用状态的 def 行。
     * 已装列表和建链时选连接器都走这里，没装过的连接器不会出现。
     */
    @Override
    public List<DatabusConnectorDefVo> listMyDefs() {
        Long userId = LoginHelper.getUserId();
        Set<Long> itemIds = installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
                .eq(DatabusInstall::getOwnerId, userId))
            .stream()
            .map(DatabusInstall::getMarketItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (itemIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> defIds = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                .in(DatabusMarketItem::getId, itemIds)
                .eq(DatabusMarketItem::getResourceType, RESOURCE_TYPE_CONNECTOR))
            .stream()
            .map(DatabusMarketItem::getSourceId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (defIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<DatabusConnectorDef> defs = defMapper.selectList(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .in(DatabusConnectorDef::getId, defIds)
            .eq(DatabusConnectorDef::getStatus, ConnectorDefStatusEnum.ENABLED.getCode())
            .orderByAsc(DatabusConnectorDef::getType));
        List<DatabusConnectorDefVo> vos = defs.stream()
            .map(this::toVo)
            .collect(Collectors.toList());
        fillOwnerCode(defs, vos);
        return vos;
    }

    /**
     * 批量回填上架者登录名（管理页连接器行渲染 @作者用）。
     * <p>
     * def 表没有作者列（create_by 存的是上传者用户 id，不是登录名），所以走市场物料行反查：
     * {@code source_id = def.id AND resource_type = CONNECTOR} 取 owner_code。一次 in 查询避免 N+1；
     * 没上架过的 def 查不到就留空——宁缺勿滥，不用 create_by 反查昵称兜底（上传人不等于上架人）。
     */
    private void fillOwnerCode(List<DatabusConnectorDef> defs, List<DatabusConnectorDefVo> vos) {
        List<Long> defIds = defs.stream()
            .map(DatabusConnectorDef::getId).filter(Objects::nonNull).toList();
        if (defIds.isEmpty()) {
            return;
        }
        // 同一 def 可能有多条物料行（驳回后重提等），按 id 升序取，后写的覆盖前面的
        Map<Long, String> ownerMap = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                .select(DatabusMarketItem::getSourceId, DatabusMarketItem::getOwnerCode)
                .eq(DatabusMarketItem::getResourceType, RESOURCE_TYPE_CONNECTOR)
                .in(DatabusMarketItem::getSourceId, defIds)
                .orderByAsc(DatabusMarketItem::getId))
            .stream()
            .filter(item -> item.getSourceId() != null && item.getOwnerCode() != null && !item.getOwnerCode().isBlank())
            .collect(Collectors.toMap(DatabusMarketItem::getSourceId, DatabusMarketItem::getOwnerCode, (a, b) -> b));
        for (int i = 0; i < defs.size(); i++) {
            String ownerCode = ownerMap.get(defs.get(i).getId());
            if (ownerCode != null) {
                vos.get(i).setOwnerCode(ownerCode);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean enable(String type, Long reviewerId, Boolean verified) {
        DatabusConnectorDef def = requireDef(type);
        // 审核即信任（§1.3）：提交人 ≠ 审批人，勾 verified 才能启用
        if (Objects.equals(def.getCreateBy(), reviewerId)) {
            throw new ServiceException("不能审核自己上传的连接器（提交人 ≠ 审批人），请换一名管理员审核");
        }
        if (!Boolean.TRUE.equals(verified)) {
            throw new ServiceException("启用即审核：请先勾选「已审核代码」并确认信任（verified）");
        }
        def.setVerified(Boolean.TRUE);
        return toggle(def, ConnectorDefStatusEnum.ENABLED.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean disable(String type) {
        return toggle(requireDef(type), ConnectorDefStatusEnum.DISABLED.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deprecate(String type) {
        DatabusConnectorDef def = requireDef(type);
        // 废弃是终态，会把 dev 通道一起堵死（DevConnectorLoader 拒绝装载已废弃的 type），
        // 而 dev 行的状态本来每次启动都被覆盖，落这个态毫无意义
        rejectDevRow(def, "不能废弃（废弃是终态，会把开发通道一起堵死）");
        if (ConnectorDefStatusEnum.DEPRECATED.getCode().equals(def.getStatus())) {
            throw new ServiceException("连接器 " + type + " 已废弃（终态不可逆）；如需彻底移除请卸载");
        }
        if (!ConnectorDefStatusEnum.ENABLED.getCode().equals(def.getStatus()) && !ConnectorDefStatusEnum.DISABLED.getCode().equals(def.getStatus())) {
            throw new ServiceException("连接器 " + type + " 尚未启用过（当前 " + def.getStatus()
                + "），无需废弃；未启用的包直接卸载即可");
        }
        // 废弃＝先停机（广播停用 + 卸载实例，包与 def 行保留）再落终态门禁
        pluginManager.broadcastSuspend(type);
        pluginManager.unload(type);
        connectorRegistry.markDeprecated(type);
        // 废弃终态同样要从市场下线：不能再有人装它
        offlineMarketItems(def);
        def.setStatus(ConnectorDefStatusEnum.DEPRECATED.getCode());
        def.setUpdateTime(LocalDateTime.now());
        return defMapper.updateById(def) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean uninstall(String type, boolean force) {
        DatabusConnectorDef def = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, type)
            .last("limit 1"));
        if (def == null) {
            throw new ServiceException("连接器不存在：" + type);
        }
        // 默认拒绝（§15.5 三档第一档）：列出在用连接数，同外键 RESTRICT 语义
        if (!force) {
            Long inUse = connectionMapper.selectCount(Wrappers.<SysDatabusConnection>lambdaQuery()
                .eq(SysDatabusConnection::getConnectorType, type));
            if (inUse != null && inUse > 0) {
                throw new ServiceException("连接器 " + type + " 仍有 " + inUse
                    + " 条在用连接，默认拒绝卸载；确认强制卸载请显式传 force=true（存量连接悬空，自然衰减）");
            }
        }
        // §4.3 四步清扫：先卸载实例（onShutdown + close loader + 注销）再删包，
        // 避免 Windows 下 jar 句柄未释放导致判删不掉
        pluginManager.unload(type);
        if (SOURCE_DEV.equals(def.getSource())) {
            // dev 行的 package_path 指向构建输出目录（target），不是平台管的包目录，删了就是删编译产物；
            // 且它的生命周期由配置项决定——删行后下次启动 loadAll 又会把行写回来，所以这里只删行
            log.warn("卸载 dev 装载连接器 {}：只删 def 行，不动构建输出目录 {}；"
                + "彻底移除请从 databus.connector.dev-plugin-dir 配置中去掉该目录", type, def.getPackagePath());
        } else {
            deletePackageDir(def.getPackagePath());
        }
        // 连接器没了，市场里的物料也要下线，避免普通用户看到一份装不了的东西
        offlineMarketItems(def);
        return defMapper.deleteById(def.getId()) > 0;
    }

    // ---------------------------- 审核「看得见」（§2.3） ----------------------------

    /**
     * 包内可疑 API 描述符（内部名，点→斜杠）：对齐 §九「机器先扫」口径——读写文件 / 外连 / 反射 / 起进程 / 开线程。
     * 仅用于审核预览，非硬门禁。
     */
    private static final Set<String> SUSPICIOUS_CONSTANTS = Set.of(
        // 读写文件 / IO
        "java/io/File", "java/io/FileInputStream", "java/io/FileOutputStream", "java/io/RandomAccessFile",
        "java/io/FileReader", "java/io/FileWriter", "java/io/PrintWriter", "java/io/BufferedReader",
        "java/io/InputStream", "java/io/OutputStream",
        "java/nio/file/Files", "java/nio/file/Paths", "java/nio/file/Path",
        // 外连网络
        "java/net/Socket", "java/net/ServerSocket", "java/net/URL", "java/net/URLConnection",
        "java/net/HttpURLConnection", "java/net/DatagramSocket", "java/net/DatagramPacket",
        "java/net/InetSocketAddress", "java/net/InetAddress",
        // 反射
        "java/lang/reflect/Method", "java/lang/reflect/Constructor", "java/lang/reflect/Field",
        "java/lang/reflect/Proxy", "java/lang/reflect/AccessibleObject",
        // 起进程
        "java/lang/ProcessBuilder", "java/lang/Process", "java/lang/Runtime",
        // 开线程
        "java/lang/Thread", "java/lang/Runnable", "java/util/concurrent/Executors",
        "java/util/concurrent/ThreadPoolExecutor", "java/util/concurrent/ExecutorService",
        // 自定义类加载
        "java/lang/ClassLoader"
    );

    /**
     * 下载原包（审核「看得见」§2.3 第 1 条）：按 type+version 定位 zip 并返回字节。
     */
    @Override
    public byte[] readPackage(String type, String version) {
        DatabusConnectorDef def = requireDefByTypeVersion(type, version);
        rejectDevRow(def, "没有原包可下载（它跑的是编译输出目录里的 class，运行期没有 zip）");
        try {
            return Files.readAllBytes(packageFileOf(def));
        } catch (IOException e) {
            throw new ServiceException("读取连接器包失败（" + type + ":" + version + "）：" + e.getMessage());
        }
    }

    /**
     * 包文件名（{@code {artifact}-{version}.zip}），下载附件名与落盘/构建产物同一规则。
     */
    @Override
    public String packageFileName(String type, String version) {
        DatabusConnectorDef def = requireDefByTypeVersion(type, version);
        return parseManifest(def.getManifestSnapshot()).packageFileName();
    }

    /**
     * 包内清单 + 关键实现类反编译预览（审核「看得见」§2.3 第 2、3 条）。
     * <p>只对「含 mainClass 的实现 jar」做常量池扫描（官方件实现类与 common 随包合打一个 jar，§2.4），
     * 驱动等第三方 jar 只列体积 / class 数 / MANIFEST 快照，不扫描（避免 JDBC 驱动自带 Socket 刷屏）。
     */
    @Override
    public Map<String, Object> inspectPackage(String type, String version) {
        DatabusConnectorDef def = requireDefByTypeVersion(type, version);
        rejectDevRow(def, "没有原包可查看（它跑的是编译输出目录里的 class，运行期没有 zip）");
        ConnectorManifest manifest = parseManifest(def.getManifestSnapshot());
        String mainClass = manifest.getMainClass();
        String mainClassEntry = mainClass == null ? null : mainClass.replace('.', '/') + ".class";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("version", version);
        result.put("engine", manifest.getEngine());
        result.put("mainClass", mainClass);

        List<Map<String, Object>> files = new ArrayList<>();
        Set<String> suspicious = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(packageFileOf(def)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] bytes = zis.readAllBytes();
                if (MANIFEST_ENTRY.equals(name)) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", name);
                    f.put("size", bytes.length);
                    f.put("kind", "manifest");
                    files.add(f);
                } else if (name.endsWith(".jar")) {
                    files.add(inspectJar(name, bytes, mainClassEntry, suspicious));
                } else {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", name);
                    f.put("size", bytes.length);
                    f.put("kind", "other");
                    files.add(f);
                }
            }
        } catch (IOException e) {
            throw new ServiceException("解析连接器包失败（" + type + ":" + version + "）：" + e.getMessage());
        }
        result.put("files", files);
        result.put("suspicious", new ArrayList<>(suspicious));
        return result;
    }

    /**
     * 按 type+version 取 def 行（下载 / 清单共用前置校验）。
     */
    private DatabusConnectorDef requireDefByTypeVersion(String type, String version) {
        DatabusConnectorDef def = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, type)
            .eq(DatabusConnectorDef::getVersion, version)
            .last("limit 1"));
        if (def == null) {
            throw new ServiceException("连接器不存在：" + type + ":" + version);
        }
        return def;
    }

    /**
     * def 行 → 落盘 zip 文件路径（plugins/{type}/{version}/{artifact}-{version}.zip）。
     * 文件名从 manifestSnapshot 推导，保证与构建产物、上传落盘同一规则。
     */
    private Path packageFileOf(DatabusConnectorDef def) {
        ConnectorManifest manifest = parseManifest(def.getManifestSnapshot());
        Path zip = Paths.get(def.getPackagePath(), manifest.packageFileName());
        if (!Files.isRegularFile(zip)) {
            throw new ServiceException("连接器包文件缺失：" + zip);
        }
        return zip;
    }

    /**
     * 解析单个 jar：体积 / 是否含 mainClass / class 数 / MANIFEST（截断）；
     * 命中实现 jar 时对其 class 做可疑常量池扫描，结果并入 suspicious。
     */
    private Map<String, Object> inspectJar(String jarName, byte[] jarBytes, String mainClassEntry, Set<String> suspicious) {
        Map<String, Object> jar = new LinkedHashMap<>();
        jar.put("name", jarName);
        jar.put("size", jarBytes.length);
        jar.put("kind", "jar");
        boolean mainJar = mainClassEntry != null && containsEntry(jarBytes, mainClassEntry);
        jar.put("mainJar", mainJar);
        List<String> classes = new ArrayList<>();
        String manifestText = null;
        try (ZipInputStream jis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry je;
            while ((je = jis.getNextEntry()) != null) {
                String n = je.getName();
                if ("META-INF/MANIFEST.MF".equals(n)) {
                    manifestText = truncate(new String(jis.readAllBytes(), StandardCharsets.UTF_8), 2048);
                } else if (n.endsWith(".class")) {
                    classes.add(n);
                    if (mainJar) {
                        for (String c : readUtf8Constants(jis.readAllBytes())) {
                            if (SUSPICIOUS_CONSTANTS.contains(c)) {
                                suspicious.add(c);
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 损坏 jar 不阻断清单：name / 体积仍可展示
        }
        jar.put("classes", classes.size());
        if (mainJar) {
            jar.put("classList", classes);
        }
        if (manifestText != null) {
            jar.put("manifest", manifestText);
        }
        return jar;
    }

    /** jar 内是否含指定 entry。 */
    private static boolean containsEntry(byte[] jarBytes, String entryName) {
        try (ZipInputStream jis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry je;
            while ((je = jis.getNextEntry()) != null) {
                if (entryName.equals(je.getName())) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // 忽略
        }
        return false;
    }

    /** 读 class 文件常量池的 UTF-8 字符串（可疑 API 扫描用，不做完整字节码解析）。 */
    private static List<String> readUtf8Constants(byte[] classBytes) {
        List<String> out = new ArrayList<>();
        if (classBytes == null || classBytes.length < 10) {
            return out;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (in.readInt() != 0xCAFEBABE) {
                return out;
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> { // Utf8
                        int len = in.readUnsignedShort();
                        out.add(new String(in.readNBytes(len), StandardCharsets.UTF_8));
                    }
                    case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 15 -> in.skipNBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); i++; } // long/double 占两个槽
                    default -> { return out; } // 未知 tag，中止
                }
            }
        } catch (IOException ignored) {
            // 畸形 class 返回已解析部分
        }
        return out;
    }

    /** 长文本截断（MANIFEST 只给关键段，防超大刷屏）。 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n…（已截断，剩余 " + (s.length() - max) + " 字符）";
    }

    // ---------------------------- 私有工具 ----------------------------

    /**
     * 启停共用：置运行层状态并接 PluginManager。
     * ENABLED → 装载实例并清两门禁；DISABLED → 广播停用 + 卸载实例（保留包）并建设禁用门禁。
     * DEPRECATED 是终态，任何启停都进不来（不可逆）；dev 目录装载的行也不进（见 rejectDevRow）。
     */
    private Boolean toggle(DatabusConnectorDef def, String status) {
        String type = def.getType();
        rejectDevRow(def, "启用/停用状态由开发通道管理，在这里改了下次启动也会被覆盖");
        if (ConnectorDefStatusEnum.DEPRECATED.getCode().equals(def.getStatus())) {
            throw new ServiceException("连接器 " + type + " 已废弃（终态不可逆），不能再启用/停用");
        }
        if (ConnectorDefStatusEnum.ENABLED.getCode().equals(status)) {
            try {
                loadConnector(def);
            } catch (Exception e) {
                throw new ServiceException("装载连接器 " + type + " 失败：" + e.getMessage());
            }
            connectorRegistry.clearPending(type);
            connectorRegistry.clearDisabled(type);
        } else {
            pluginManager.broadcastSuspend(type);
            pluginManager.unload(type);
            connectorRegistry.markDisabled(type);
            // 停用后市场里不该再挂着它能装：直接下架，读端查询就不需要跨表判 def 状态
            offlineMarketItems(def);
        }
        def.setStatus(status);
        def.setUpdateTime(LocalDateTime.now());
        return defMapper.updateById(def) > 0;
    }

    /**
     * 连接器变得不可用（停用/废弃/卸载）时，把它的市场物料一起下线。
     * 市场只挂能装的东西，这条不变式由写入端维持，读端查询就不用再跨表判断 def 状态。
     */
    private void offlineMarketItems(DatabusConnectorDef def) {
        if (def == null || def.getId() == null) {
            return;
        }
        DatabusMarketItem update = new DatabusMarketItem();
        update.setStatus(MarketStates.STATUS_OFFLINE);
        marketItemMapper.update(update, Wrappers.<DatabusMarketItem>lambdaUpdate()
            .eq(DatabusMarketItem::getResourceType, RESOURCE_TYPE_CONNECTOR)
            .eq(DatabusMarketItem::getSourceId, def.getId())
            .eq(DatabusMarketItem::getStatus, MarketStates.STATUS_ONLINE));
    }

    /**
     * 按 type 取 def 行，不存在即报错（启停/审核共用的前置校验）。
     */
    private DatabusConnectorDef requireDef(String type) {
        DatabusConnectorDef def = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, type)
            .last("limit 1"));
        if (def == null) {
            throw new ServiceException("连接器不存在：" + type);
        }
        return def;
    }

    /**
     * dev 目录装载的行不接受任何「针对包或启停状态」的操作（方案 §5.6）：它直接跑编译输出目录里的
     * class，运行期没有 zip 这个实体；version / status / package_path 每次启动都被开发通道覆盖，
     * 在界面上改了重启即失效。这类行只留一个动作——「重新加载」
     * （{@code DatabusConnectorDevController#reload}，仅 dev profile 存在）。
     *
     * @param reason 本次被拒的原因，拼进提示语（各入口说法不同，出路统一是「打包上传」）
     */
    private void rejectDevRow(DatabusConnectorDef def, String reason) {
        if (SOURCE_DEV.equals(def.getSource())) {
            throw new ServiceException("连接器 " + def.getType() + " 是 dev 目录装载的，" + reason
                + "；改代码后调「重新加载」即可生效。需要走审核、启停、下载包的完整流程，请 mvn package 后在「上架物料」里选择连接器并上传该 zip");
        }
    }

    /**
     * 按 def 行装载连接器（解析 manifest 快照 → PluginManager 解压/建 loader/实例化/注册）。
     */
    private void loadConnector(DatabusConnectorDef def) {
        ConnectorManifest manifest = parseManifest(def.getManifestSnapshot());
        pluginManager.load(def.getType(), Paths.get(def.getPackagePath()), manifest);
    }

    /**
     * 解包提取根级 manifest.json（同时遍历校验所有 entry 路径，防 zip 路径穿越）。
     */
    private String extractManifest(MultipartFile file) {
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name != null && name.contains("..")) {
                    throw new ServiceException("包内路径非法（不允许 ..）：" + name);
                }
                if (MANIFEST_ENTRY.equals(name)) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new ServiceException("包解析失败（须为含 manifest.json 的 zip）：" + e.getMessage());
        }
        throw new ServiceException("包内缺少根级 " + MANIFEST_ENTRY);
    }

    /**
     * manifest JSON → 解析模型（解析失败抛给上传者）。
     */
    private ConnectorManifest parseManifest(String manifestJson) {
        try {
            return objectMapper.readValue(manifestJson, ConnectorManifest.class);
        } catch (JacksonException e) {
            throw new ServiceException("manifest.json 解析失败：" + e.getOriginalMessage());
        }
    }

    /**
     * def 行 → Vo：manifest 快照解析回填 label / icon / operations / configSchema
     * （快照损坏时字段置空不抛——列表可用性优先，落库前已过 validate）。
     */
    private DatabusConnectorDefVo toVo(DatabusConnectorDef def) {
        DatabusConnectorDefVo vo = new DatabusConnectorDefVo();
        vo.setId(def.getId());
        vo.setType(def.getType());
        vo.setVersion(def.getVersion());
        vo.setEngine(def.getEngine());
        vo.setStatus(def.getStatus());
        vo.setSource(def.getSource());
        vo.setVerified(def.getVerified());
        vo.setPackagePath(def.getPackagePath());
        vo.setCreateBy(def.getCreateBy());
        vo.setCreateTime(def.getCreateTime());
        vo.setUpdateTime(def.getUpdateTime());
        try {
            ConnectorManifest manifest = objectMapper.readValue(def.getManifestSnapshot(), ConnectorManifest.class);
            vo.setArtifact(manifest.getArtifact());
            vo.setLabel(manifest.getLabel());
            vo.setIcon(manifest.getIcon());
            vo.setOperations(manifest.getOperations());
            vo.setConfigSchema(manifest.getConfigSchema());
        } catch (Exception ignored) {
            // 快照异常不阻断列表；上传链路已保证落库快照合法
        }
        // 已装载时用运行态 describe() 覆盖 operations/configSchema（代码是活的，manifest 是构建期快照，§2.3）
        Connector connector = connectorRegistry.get(def.getType());
        if (connector != null) {
            ConnectorDescriptor descriptor = connector.describe();
            vo.setOperations(descriptor.getOperations());
            vo.setConfigSchema(descriptor.getConfigSchema());
        }
        // 运行态健康（§4.2.1）：内存现算不落库，与组件的 GET /component/script/runtime 同一口径
        PluginManager.ConnectorHealth health = pluginManager.healthOf(def.getType());
        vo.setHealth(health.health());
        vo.setHealthReason(health.reason());
        return vo;
    }

    /**
     * 递归删包目录（删除失败不阻断删行——行删了目录成孤儿，重传同版本会覆盖）。
     */
    private void deletePackageDir(String packagePath) {
        if (packagePath == null || packagePath.isBlank()) {
            return;
        }
        Path dir = Paths.get(packagePath);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 单文件删失败继续（只读/占用），整体失败由孤儿目录兜底
                }
            });
        } catch (IOException ignored) {
            // 目录级失败不阻断
        }
    }

}