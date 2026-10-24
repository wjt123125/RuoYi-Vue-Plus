package org.dromara.databus.service;

import org.dromara.databus.domain.vo.DatabusConnectorDefVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 连接器运行层 Service 接口（万物皆包，databus-material-market.md §15.5/§15.7）。
 * <p>
 * 四端点对应能力：上传包（校验+落盘+入库，同 type 升版本＝热更新）、
 * def 合并视图（内置∪def 行）、启停（ENABLED/SUSPENDED）、卸载（默认拒绝在用连接）。
 *
 * @author databus
 */
public interface IDatabusConnectorService {

    /**
     * 上传连接器包（zip＝manifest.json + lib/）。
     * <p>
     * 流程：解包校验 manifest（必填/命名/engine 枚举）→ type 冲突检测
     * （同 type 同 version 报错；升版本＝热更新 def 行，status 保持）→
     * 落盘 plugins/{type}/{version}/ → 入库（新 type 默认 SUSPENDED，enable 为显式治理动作）。
     *
     * @param file   连接器包（zip）
     * @param userId 上传者 id
     * @return def 视图（含 manifest 解析回填的 label / icon / operations / configSchema）
     */
    DatabusConnectorDefVo upload(MultipartFile file, Long userId);

    /**
     * def 合并视图：内置（ConnectorRegistry 扫描注册，视为 ENABLED）∪ def 行
     * （含包上传 / 市场来源）。同 type 时 def 行优先（携带运行层状态）。
     * 驱动连接管理页 CONNECTOR_OPTIONS 动态化（取代前端硬编码 bpmHttp）。
     */
    List<DatabusConnectorDefVo> listDefs();

    /**
     * 启用（ENABLED）：def 行进可选列表；v1 运行时加载（PluginClassLoader）为后续分期。
     */
    Boolean enable(String type);

    /**
     * 暂停（SUSPENDED）：挡新建连接（defs 列表状态非 ENABLED），存量 Connection 与数据保留。
     */
    Boolean disable(String type);

    /**
     * 卸载（三档语义的 v1 收敛，§15.5）。
     * <p>
     * 默认拒绝：在用连接（sys_databus_connection.connector_type）非 0 时抛错并列出计数
     * （RESTRICT 语义）；force=true 管理员强制：跳过检查、删 def 行 + 删包目录，
     * 存量连接行保留（悬空自然衰减＝软卸载语义，v1 不做连接级联删除）。
     * 软卸载单独档不做：SUSPENDED 已覆盖「摘出可选列表、存量保留」。
     *
     * @param type  连接器类型键
     * @param force 管理员强制卸载（跳过在用连接检查）
     */
    Boolean uninstall(String type, boolean force);

}
