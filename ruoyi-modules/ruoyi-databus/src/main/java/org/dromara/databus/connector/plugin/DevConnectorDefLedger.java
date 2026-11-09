package org.dromara.databus.connector.plugin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.connector.ConnectorManifest;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.enums.ConnectorDefStatusEnum;
import org.dromara.databus.enums.OwnerTypeEnum;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DEV 连接器台账（{@code databus_connector_def} 中 {@code source=DEV} 的行）的写入与对账
 * （从 DevConnectorLoader 拆出）。
 *
 * <p>职责边界：只维护 DEV 行的内容与存续，不装载、不碰运行态 registry。
 *
 * <p>台账与 {@code dev-plugin-dir} 严格一致：启动装载前先读各目录 manifest 得到 type 集合，
 * 装载完成后调用 {@link #reconcileDevRows} 删除配置里已没有的 DEV 行。
 *
 * @author databus
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevConnectorDefLedger {

    /**
     * 来源角标：dev 目录装载（无特权，仅展示用）
     */
    private static final String SOURCE_DEV = "DEV";

    /**
     * 编译输出目录里的清单文件名（源在 {@code src/main/resources}，IDE 编译同步过来）
     */
    private static final String MANIFEST_FILE = "manifest.json";

    private final DatabusConnectorDefMapper defMapper;

    /**
     * Jackson 3 容器 bean（tools.jackson；携全局序列化定制）
     */
    private final ObjectMapper objectMapper;

    /**
     * 写运行层台账：新增行不设 {@code verified}（走 DB 默认 0）与 {@code createBy}；
     * 更新已有行只刷 version/engine/manifest/package_path/status/source，
     * <b>不触碰 verified</b>（§1.3：信任档由审批人勾选，不由 source 自动推导）。
     *
     * <p>{@code package_path} 写 class 目录的父目录（{@code target}），与列定义一致
     * （该列是「zip 落盘目录」，{@code mvn package} 产出的 {artifact}-{version}.zip 就在这一层）。
     * dev 装载自己不读这一列（目录来自配置项 dev-plugin-dir），写对它是为了让
     * 「package_path + manifest 推导出的文件名 = zip 全路径」这条算法对全表都成立，不必按来源分支。
     */
    public void upsertDef(DatabusConnectorDef existing, String type, ConnectorManifest manifest,
                          String manifestJson, Path classesDir) {
        LocalDateTime now = LocalDateTime.now();
        String packagePath = classesDir.getParent().toString().replace('\\', '/');
        if (existing == null) {
            DatabusConnectorDef row = new DatabusConnectorDef();
            row.setType(type);
            row.setVersion(manifest.getVersion());
            row.setBuildNo(manifest.getBuildNo() == null ? 0 : manifest.getBuildNo());
            row.setEngine(manifest.getEngine());
            row.setManifestSnapshot(manifestJson);
            row.setPackagePath(packagePath);
            row.setStatus(ConnectorDefStatusEnum.ENABLED.getCode());
            row.setSource(SOURCE_DEV);
            row.setOwnerType(OwnerTypeEnum.USER.getCode());
            row.setCreateTime(now);
            row.setUpdateTime(now);
            defMapper.insert(row);
            // dev 目录一个 type 只对应一个版本：manifest 改过 version 时，旧 DEV 版本行不再有效
            defMapper.delete(Wrappers.<DatabusConnectorDef>lambdaQuery()
                .eq(DatabusConnectorDef::getType, type)
                .eq(DatabusConnectorDef::getSource, SOURCE_DEV)
                .ne(DatabusConnectorDef::getVersion, manifest.getVersion()));
            return;
        }
        existing.setVersion(manifest.getVersion());
        existing.setBuildNo(manifest.getBuildNo() == null ? 0 : manifest.getBuildNo());
        existing.setEngine(manifest.getEngine());
        existing.setManifestSnapshot(manifestJson);
        existing.setPackagePath(packagePath);
        existing.setStatus(ConnectorDefStatusEnum.ENABLED.getCode());
        existing.setSource(SOURCE_DEV);
        existing.setOwnerType(OwnerTypeEnum.USER.getCode());
        existing.setUpdateTime(now);
        defMapper.updateById(existing);
    }

    /**
     * 只读目录里的 {@code manifest.json} 取 type，不实例化、不校验（校验交给装载环节）。
     *
     * <p>对账需要提前知道「这个目录代表哪个 type」：否则装载失败的目录对不上任何 type，
     * 它原本的 DEV 行会被当成残留删掉。读不出来（目录未编译 / 缺 manifest / JSON 非法）返回 null，
     * 与「目录已被移出配置」等价——该行会在对账时清理，具体原因由装载环节打印。
     */
    public String readManifestTypeQuietly(Path classesDir) {
        Path manifestPath = classesDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) {
            return null;
        }
        try {
            ConnectorManifest manifest = objectMapper.readValue(Files.readString(manifestPath), ConnectorManifest.class);
            String type = manifest.getType();
            return type == null || type.isBlank() ? null : type.trim();
        } catch (IOException | JacksonException e) {
            log.warn("DEV 模式：读取 {} 失败，该目录对应的连接器不参与台账对账：{}", manifestPath, e.getMessage());
            return null;
        }
    }

    /**
     * DEV 台账对账：数据库里的 DEV 行必须与 {@code dev-plugin-dir} 当前配置一一对应。
     * <ul>
     *   <li>配置里没有的 type → 删行（配置留空即清空全部 DEV 行，不需要再人工 {@code delete}）；</li>
     *   <li>配置里有、行不存在的 type → 由装载成功后的 {@link #upsertDef} 建行；</li>
     *   <li>配置里有、但装载失败的 type → 行保留，列表页照常显示失败原因，修好后重启即恢复。</li>
     * </ul>
     * 只处理 {@code source=DEV} 的行；上传包与市场安装的行不参与对账。
     */
    public void reconcileDevRows(Set<String> configuredTypes) {
        List<DatabusConnectorDef> devRows = defMapper.selectList(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getSource, SOURCE_DEV));
        List<String> removed = new ArrayList<>();
        for (DatabusConnectorDef row : devRows) {
            if (configuredTypes.contains(row.getType())) {
                continue;
            }
            defMapper.deleteById(row.getId());
            removed.add(row.getType());
        }
        if (!removed.isEmpty()) {
            log.warn("DEV 模式：以下连接器已不在 databus.connector.dev-plugin-dir 配置中，"
                + "DEV 台账记录已同步删除：{}", removed);
        }
    }
}
