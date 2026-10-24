package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorDescriptor;
import org.dromara.databus.connector.ConnectorManifest;
import org.dromara.databus.connector.ConnectorRegistry;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.SysDatabusConnection;
import org.dromara.databus.domain.vo.DatabusConnectorDefVo;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.SysDatabusConnectionMapper;
import org.dromara.databus.service.IDatabusConnectorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 连接器运行层 Service 实现（万物皆包，databus-material-market.md §15.5/§15.7）。
 *
 * <p>上传链路：zip 校验（根级 manifest.json + 路径防穿越）→ manifest 必填/命名/engine
 * 枚举校验 → type 冲突检测（同 type 同 version 报错；升版本＝热更新 def 行）→
 * 落盘 {@code plugins/{type}/{version}/} → 入库。新 type 默认 SUSPENDED：
 * 审核即信任（§15.11），enable 是治理者的显式动作。
 *
 * <p>defs 合并视图：内置（ConnectorRegistry 扫描注册，视为 ENABLED）∪ def 行；
 * 同 type 时 def 行优先（携带运行层状态，可对内置连接器做包级遮蔽）。
 *
 * <p>卸载（§15.5 三档的 v1 收敛）：默认拒绝（在用连接计数 RESTRICT）→
 * force=true 强制（删行删包，存量连接悬空自然衰减）；软卸载单独档不做，
 * SUSPENDED 已覆盖「摘出可选列表、存量保留」。
 *
 * <p>包目录可配置 {@code databus.connector.package-dir}（默认相对工作目录 {@code plugins}）。
 * 运行时动态加载（PluginClassLoader、jvm 档双版本并存）为 15.9 落地顺序第 4 步，
 * 本期 def 行 ENABLED 只控制 defs 可选性，不代表 Registry 可查。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusConnectorServiceImpl implements IDatabusConnectorService {

    /**
     * 运行层状态：启用
     */
    private static final String STATUS_ENABLED = "ENABLED";

    /**
     * 运行层状态：暂停（挡新建连接，存量保留）
     */
    private static final String STATUS_SUSPENDED = "SUSPENDED";

    /**
     * 来源：市场 tab 内上传
     */
    private static final String SOURCE_UPLOAD = "UPLOAD";

    /**
     * 上传包体上限（50MB；manifest 声明式包通常几 KB，jvm 档 jar 为主要体量来源）
     */
    private static final long MAX_PACKAGE_BYTES = 50L * 1024 * 1024;

    /**
     * 包内清单文件名（zip 根级，必选）
     */
    private static final String MANIFEST_ENTRY = "manifest.json";

    /**
     * 落盘文件名（固定名，目录即版本隔离）
     */
    private static final String PACKAGE_FILE_NAME = "connector-package.zip";

    private final DatabusConnectorDefMapper defMapper;

    private final SysDatabusConnectionMapper connectionMapper;

    private final ConnectorRegistry connectorRegistry;

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

        // type 冲突检测（§15.5 上传行）：同 type 同 version 报错；升版本＝热更新
        DatabusConnectorDef existing = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, manifest.getType())
            .last("limit 1"));
        if (existing != null && manifest.getVersion().equals(existing.getVersion())) {
            throw new ServiceException("连接器 " + manifest.getType() + " 版本 " + manifest.getVersion()
                + " 已存在，请升级版本号后重新上传（同 type 升版本＝热更新）");
        }

        // 落盘 plugins/{type}/{version}/connector-package.zip（type/version 已正则约束，无穿越面）
        Path dir = Paths.get(packageDir, manifest.getType(), manifest.getVersion());
        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(PACKAGE_FILE_NAME).toAbsolutePath());
        } catch (IOException e) {
            throw new ServiceException("包落盘失败（目录 " + dir + "）：" + e.getMessage());
        }
        String packagePath = dir.toString().replace('\\', '/');

        LocalDateTime now = LocalDateTime.now();
        DatabusConnectorDef row;
        if (existing == null) {
            row = new DatabusConnectorDef();
            row.setType(manifest.getType());
            row.setVersion(manifest.getVersion());
            row.setEngine(manifest.getEngine());
            row.setManifestSnapshot(manifestJson);
            row.setPackagePath(packagePath);
            // 审核即信任（§15.11）：新包默认暂停，enable 是治理者显式动作
            row.setStatus(STATUS_SUSPENDED);
            row.setSource(SOURCE_UPLOAD);
            row.setCreateBy(userId);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            defMapper.insert(row);
        } else {
            // 热更新（§15.5）：刷版本/manifest/包路径，status 保持不停机；
            // jvm 档双版本并存是 PluginClassLoader 加载层行为，数据层单行承载
            existing.setVersion(manifest.getVersion());
            existing.setEngine(manifest.getEngine());
            existing.setManifestSnapshot(manifestJson);
            existing.setPackagePath(packagePath);
            existing.setUpdateTime(now);
            defMapper.updateById(existing);
            row = existing;
        }
        return toVo(row);
    }

    @Override
    public List<DatabusConnectorDefVo> listDefs() {
        Map<String, DatabusConnectorDef> defMap = defMapper.selectList(Wrappers.<DatabusConnectorDef>lambdaQuery())
            .stream()
            .collect(Collectors.toMap(DatabusConnectorDef::getType, d -> d, (a, b) -> a));
        List<DatabusConnectorDefVo> result = new ArrayList<>();
        // 内置（Spring 扫描注册）：无 def 行遮蔽时视为 ENABLED（type/label/icon 取 describe()）
        for (Map.Entry<String, Connector> entry : connectorRegistry.all().entrySet()) {
            if (defMap.containsKey(entry.getKey())) {
                continue;
            }
            ConnectorDescriptor descriptor = entry.getValue().describe();
            DatabusConnectorDefVo vo = new DatabusConnectorDefVo();
            vo.setType(entry.getKey());
            vo.setLabel(descriptor.getLabel());
            vo.setIcon(descriptor.getIcon());
            vo.setOperations(descriptor.getOperations());
            vo.setConfigSchema(descriptor.getConfigSchema());
            vo.setStatus(STATUS_ENABLED);
            vo.setBuiltin(Boolean.TRUE);
            result.add(vo);
        }
        // def 行（包上传 / 市场来源；同 type 遮蔽内置，携带运行层状态）
        for (DatabusConnectorDef def : defMap.values()) {
            result.add(toVo(def));
        }
        result.sort(Comparator.comparing(DatabusConnectorDefVo::getType));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean enable(String type) {
        return toggle(type, STATUS_ENABLED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean disable(String type) {
        return toggle(type, STATUS_SUSPENDED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean uninstall(String type, boolean force) {
        DatabusConnectorDef def = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, type)
            .last("limit 1"));
        if (def == null) {
            if (connectorRegistry.all().containsKey(type)) {
                throw new ServiceException("内置连接器 " + type + " 随应用发布，不支持卸载");
            }
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
        deletePackageDir(def.getPackagePath());
        // 物理删行（databus_connector_def 无逻辑删除）；JDBC 档 DriverShim/deregister 属运行层加载器（后续分期）
        return defMapper.deleteById(def.getId()) > 0;
    }

    // ---------------------------- 私有工具 ----------------------------

    /**
     * 启停共用：置运行层状态（SUSPENDED 挡新建连接不拦存量，§15.5 暂停行）。
     */
    private Boolean toggle(String type, String status) {
        DatabusConnectorDef def = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, type)
            .last("limit 1"));
        if (def == null) {
            if (connectorRegistry.all().containsKey(type)) {
                throw new ServiceException("内置连接器 " + type + " 随应用启停（重启/回滚应用），不走 def 行启停");
            }
            throw new ServiceException("连接器不存在：" + type);
        }
        def.setStatus(status);
        def.setUpdateTime(LocalDateTime.now());
        return defMapper.updateById(def) > 0;
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
        vo.setBuiltin(Boolean.FALSE);
        vo.setPackagePath(def.getPackagePath());
        vo.setCreateBy(def.getCreateBy());
        vo.setCreateTime(def.getCreateTime());
        vo.setUpdateTime(def.getUpdateTime());
        try {
            ConnectorManifest manifest = objectMapper.readValue(def.getManifestSnapshot(), ConnectorManifest.class);
            vo.setLabel(manifest.getLabel());
            vo.setIcon(manifest.getIcon());
            vo.setOperations(manifest.getOperations());
            vo.setConfigSchema(manifest.getConfigSchema());
        } catch (Exception ignored) {
            // 快照异常不阻断列表；上传链路已保证落库快照合法
        }
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
