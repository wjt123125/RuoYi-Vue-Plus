package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 物料实现预览视图对象（2026-10-24 装前可看：不看实现凭什么装）。
 * <p>
 * 只对 {@code ONLINE} 物料开放，避免审核中/已下架的草稿实现外泄。
 * 分层披露口径：组件给脚本与契约（组件的全部就是脚本）；链路只给节点清单
 * （类型/编码/名称/引用关系），参数细节留在只读画布，需多点一次。
 *
 * @author databus
 * @since 2026/10/24
 */
@Data
public class DatabusMarketPreviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 资源类型：COMPONENT / CHAIN
     */
    private String resourceType;

    /**
     * 链路源 id（前端「打开只读画布」用；组件为空）
     */
    private Long chainId;

    /**
     * 组件编码（CHAIN 为空）
     */
    private String code;

    /**
     * 组件版本（CHAIN 为空）
     */
    private String version;

    /**
     * 脚本语言（CHAIN 为空）
     */
    private String scriptLang;

    /**
     * 描述（组件取组件描述，链路取链路备注）
     */
    private String description;

    /**
     * 组件脚本正文（CHAIN 为空；上架即公开，含内网信息的写法由作者自负）
     */
    private String scriptBody;

    /**
     * 配置参数 Schema（JSON 原文，前端只读展示）
     */
    private String paramSchema;

    /**
     * 输入数据结构 Schema（JSON 原文）
     */
    private String inputSchema;

    /**
     * 输出数据结构 Schema（JSON 原文）
     */
    private String outputSchema;

    /**
     * 链路节点清单（仅 CHAIN：结构关键字与业务叶子并列，口径与审核预览同源）
     */
    private List<DatabusMarketReviewDetailVo.NodeVo> nodes;
}
