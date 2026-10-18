package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 市场物料视图对象（市场页卡片 / 详情用）
 * <p>
 * databus_market_item 行 + 消费侧附加标记：installed / localResourceId 为「当前用户是否已装」回填，
 * mine 为「当前用户是否为本物料上架者」回填（用于卡片动作三态：安装 / 使用 / 去管理）。
 *
 * @author databus
 */
@Data
public class DatabusMarketItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 上架者 id
     */
    private Long ownerId;

    /**
     * 上架者展示码（登录名）
     */
    private String ownerCode;

    /**
     * 物料类型（COMPONENT / CHAIN）
     */
    private String resourceType;

    /**
     * 源资源 id
     */
    private Long sourceId;

    /**
     * 展示码 {@code {ownerCode}/{code}}
     */
    private String codeDisplay;

    /**
     * 名称
     */
    private String name;

    /**
     * 简介
     */
    private String summary;

    /**
     * 图标
     */
    private String icon;

    /**
     * 色值
     */
    private String color;

    /**
     * 分类id
     */
    private Long categoryId;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 状态（DRAFT / PENDING / APPROVED / REJECTED / ONLINE / OFFLINE）
     */
    private String status;

    /**
     * 安装数（缓存）
     */
    private Integer installCount;

    /**
     * 被引用数（缓存）
     */
    private Integer refCount;

    /**
     * fork 改后发布数（缓存）
     */
    private Integer forkPublishCount;

    /**
     * 当前用户是否已安装（列表/详情接口回填）
     */
    private Boolean installed;

    /**
     * 已装时本地副本 id（回填，供「使用」直达）
     */
    private Long localResourceId;

    /**
     * 当前用户是否为本物料的原上架者（列表/详情接口回填；true 表示这条就是「我的上架」，不应再显示「安装」）
     */
    private Boolean mine;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 备注（审核驳回理由等）
     */
    private String remark;

    /**
     * 审核人 id（治理者）
     */
    private Long reviewBy;

    /**
     * 审核时间
     */
    private LocalDateTime reviewTime;

}