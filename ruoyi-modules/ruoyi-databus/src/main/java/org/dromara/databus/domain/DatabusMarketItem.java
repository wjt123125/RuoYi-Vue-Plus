package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 市场物料对象 databus_market_item
 * <p>
 * 物料市场的集市货架（用户共治，非官方商城；设计正本 databus-material-market.md §十）：
 * 一行＝一个已提交上架的组件/链路，承载展示信息（名称/简介/图标/色值/分类/标签）与
 * 状态机（DRAFT→PENDING→APPROVED/REJECTED→ONLINE→OFFLINE）。
 * <p>
 * 计数三列（install_count / ref_count / fork_publish_count）为缓存列，异步刷新，不进实时路径。
 * 精简列：仅 create_time/update_time，无审计字段、无逻辑删除（删原件即物理删，作者主权）。
 *
 * @author databus
 */
@Data
@TableName(value = "databus_market_item", autoResultMap = true)
public class DatabusMarketItem {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 上架者 id（命名空间键）
     */
    private Long ownerId;

    /**
     * 上架者展示码（登录名）
     */
    private String ownerCode;

    /**
     * 物料类型（COMPONENT / CHAIN / CONNECTOR——连接器安装语义＝enable，不写 databus_install）
     */
    private String resourceType;

    /**
     * 源资源 id（链路主键 / 组件 DB 行 id / 连接器 def 行 id）
     */
    private Long sourceId;

    /**
     * 展示码 {@code {ownerCode}/{code}}（人看，非运行时标识）
     */
    private String codeDisplay;

    /**
     * 名称（上架时取自源资源）
     */
    private String name;

    /**
     * 简介（上架者填写）
     */
    private String summary;

    /**
     * 图标（上架时取自源资源）
     */
    private String icon;

    /**
     * 色值（上架时取自源资源）
     */
    private String color;

    /**
     * 分类id（databus_market_category 多级平表）
     */
    private Long categoryId;

    /**
     * 标签列表（tags 列 JSON 数组自动互转）
     */
    @TableField(value = "tags", typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /**
     * 状态（DRAFT / PENDING / APPROVED / REJECTED / ONLINE / OFFLINE）
     */
    private String status;

    /**
     * 安装数（缓存：databus_install 中指向本物料的 fork 记录条数）
     */
    private Integer installCount;

    /**
     * 被引用数（缓存：下游链路 nodeTree 引用源编码的次数）
     */
    private Integer refCount;

    /**
     * fork 改后发布数（缓存：已装副本被改并发布的条数）
     */
    private Integer forkPublishCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 备注（驳回理由等；BaseEntity 不含 remark，表列自持）
     */
    private String remark;

    /**
     * 修改说明（P1 驳回-重提闭环：作者被驳回后重提时填写，审核者复核用）
     */
    private String modifyNote;

    /**
     * 审核人 id（治理者；驳回 / 通过均记）
     */
    private Long reviewBy;

    /**
     * 审核时间
     */
    private LocalDateTime reviewTime;

}