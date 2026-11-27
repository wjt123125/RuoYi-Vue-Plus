package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回退预检与回退结果共用的资源条目：被处置的链路 / 组件草稿，以及处置方式。
 * <p>
 * 预检接口用它表达「将撤销 / 将跳过 / 受影响」，回退接口用它回报实际执行结果，
 * 两端字段一致，前端不需要两套模型。
 *
 * @author databus
 */
@Data
public class AgentRollbackVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 资源类型：{@code CHAIN} 链路 / {@code COMPONENT_DRAFT} 组件草稿
     */
    private String resourceType;

    /**
     * 资源 id（链路 id 或草稿组件 id）
     */
    private Long resourceId;

    /**
     * 资源编码（链路编码 / 组件编码），预检清单展示与引用提示用
     */
    private String resourceCode;

    /**
     * 资源名称（链路名称 / 组件名称），预检清单展示用
     */
    private String resourceName;

    /**
     * 处置动作：{@code DELETE} 删除 / {@code RESTORE} 写回 / {@code SKIP} 跳过 /
     * {@code REFERENCE} 引用影响（仅预检的受影响清单）
     */
    private String action;

    /**
     * true 表示整条已删除；false 表示写回或跳过
     */
    private Boolean deleted;

    /**
     * 跳过或被影响的原因，直接展示给用户
     */
    private String reason;

}