package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 安装/血缘视图对象（管理页「我的已装」行列表用）
 * <p>
 * databus_install 行 + 展示字段：name/summary/icon/color/enabled 优先取本地副本（我方物料的
 * 当前态），resourceType 取市场物料（源已删时按本地副本落在哪张表推断）。
 *
 * @author databus
 */
@Data
public class DatabusMarketInstallVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 安装者 id
     */
    private Long ownerId;

    /**
     * 市场物料 id
     */
    private Long marketItemId;

    /**
     * 本地副本 id
     */
    private Long localResourceId;

    /**
     * 安装模式（FORK / SHARED / FAVORITE）
     */
    private String mode;

    /**
     * 目录归属（组件 SHARED 引用挂树；null=未归组）
     */
    private Long directoryId;

    /**
     * 落快照时的逻辑源码指纹
     */
    private String contentHash;

    /**
     * 安装时间
     */
    private LocalDateTime createTime;

    /**
     * 物料类型（COMPONENT / CHAIN）
     */
    private String resourceType;

    /**
     * 名称（本地副本优先）
     */
    private String name;

    /**
     * 简介（市场物料优先，回退本地备注/描述）
     */
    private String summary;

    /**
     * 图标（本地副本优先）
     */
    private String icon;

    /**
     * 色值（本地副本优先）
     */
    private String color;

    /**
     * 本地副本启停态（组件停用=true；链路无开关，取已发布=true）
     */
    private Boolean enabled;

    /**
     * 组件编码（SHARED 组件引用叶子的 open 通道：前端按 code 反查台账行打开详情）
     */
    private String componentCode;

    /**
     * 上架者登录名（取自市场物料行 owner_code；物料行已删时为空，前端不渲染 @作者）
     */
    private String ownerCode;

    /**
     * 链路闭包级联清单（decisions §十四-1/2：全 fork，每条独立成行；仅 CHAIN 安装返回）。
     * <p>
     * 含主链与全部级联子链（post-order 序，主链居末）：NEW＝本次新复制；REUSED＝命中既有同源
     * fork 品（decisions §十四-2 撞名显式复用），安装结果提示「使用你现有副本」。
     */
    private List<CascadeItemVo> cascade;

    /**
     * 级联清单条目（decisions §十四-2）：一条闭包链路的复制结果。
     */
    @Data
    public static class CascadeItemVo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 源链编码（引用重定向前）
         */
        private String sourceCode;

        /**
         * 副本编码（REUSED 时即既有副本编码）
         */
        private String newCode;

        /**
         * 源链名称
         */
        private String sourceName;

        /**
         * 复制状态（NEW＝新复制 / REUSED＝复用既有同源副本）
         */
        private String status;
    }

}