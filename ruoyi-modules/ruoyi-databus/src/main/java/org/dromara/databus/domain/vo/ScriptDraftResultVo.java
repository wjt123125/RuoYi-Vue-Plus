package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 脚本草稿落库结果：草稿行信息 + 编译物化的契约 + 危险 API 自查提示。
 * <p>
 * 草稿行状态固定 DRAFT，不在物料面板投放、不注册 FlowBus；要生效必须由人在审阅页点采纳。
 *
 * @author databus
 */
@Data
public class ScriptDraftResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 草稿组件主键（审阅页与采纳端点用）
     */
    private Long id;

    /**
     * 组件编码
     */
    private String componentCode;

    /**
     * 组件名称
     */
    private String componentName;

    /**
     * 组件状态（固定 DRAFT）
     */
    private String status;

    /**
     * 物化：节点类型
     */
    private String nodeType;

    /**
     * 物化：参数 schema 体
     */
    private String paramSchema;

    /**
     * 物化：配置示例
     */
    private String dataExample;

    /**
     * 反射出的字段数
     */
    private Integer fieldCount;

    /**
     * 危险 API 命中提示（不阻断落草稿，随草稿交给审阅人）
     */
    private List<String> risks;

}