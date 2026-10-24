package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 连接器运行层台账对象 databus_connector_def（万物皆包，databus-material-market.md §15.6）
 *
 * <p>一行＝一个连接器类型（type 全局唯一）。与市场层 databus_market_item 解耦：
 * 市场管买卖（PENDING/ONLINE...），本表管运行（ENABLED / SUSPENDED / DEPRECATED）；
 * 市场下线只挡新安装，存量 def 照常运行（§15.4）。
 *
 * <p>type 唯一键承载「冲突检测」：不存在＝新插入；同 type 同 version＝报错；
 * 同 type 升版本＝热更新本行（status 保持，不停机）。jvm 档双版本并存是
 * PluginClassLoader 加载层行为（§15.5），数据层 v1 单行承载。
 *
 * <p>内置连接器（Spring 扫描注册，如 bpmHttp）不入本表；defs 接口为合并视图。
 * 精简列：create_by + create_time / update_time，无逻辑删除（卸载＝物理删行 + 删包）。
 *
 * @author databus
 */
@Data
@TableName("databus_connector_def")
public class DatabusConnectorDef {

    /**
     * 主键 id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 类型键（全局唯一；与 Connector#getType 对齐，卸载保护范围）
     */
    private String type;

    /**
     * 版本号（manifest 声明；同 type 升版本＝热更新）
     */
    private String version;

    /**
     * 引擎档（http / jvm / native，§15.2 光谱）
     */
    private String engine;

    /**
     * manifest.json 原文快照（驱动表单动态渲染与物料生成）
     */
    private String manifestSnapshot;

    /**
     * zip 落盘目录（plugins/{type}/{version}）
     */
    private String packagePath;

    /**
     * 运行层状态（ENABLED / SUSPENDED / DEPRECATED）
     */
    private String status;

    /**
     * 来源（MARKET / UPLOAD / AI / ADMIN）
     */
    private String source;

    /**
     * 上传者 id
     */
    private Long createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
