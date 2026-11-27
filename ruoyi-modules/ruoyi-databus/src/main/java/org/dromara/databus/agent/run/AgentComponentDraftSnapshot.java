package org.dromara.databus.agent.run;

import lombok.Data;
import org.dromara.databus.el.bean.ChainInputParam;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 组件草稿快照载荷（databus_agent_snapshot.payload 的 JSON 结构，resourceType=COMPONENT_DRAFT）。
 * <p>
 * operation=CREATE 时只存定位信息（componentId/componentCode/componentName），回滚语义是删掉草稿行；
 * operation=UPDATE 时额外存改前内容，回滚按它重走草稿保存把正文与元信息写回。
 * <p>
 * 与 {@link AgentChainSnapshot} 分开建类：两类资源的可回退字段完全不同，塞进一个类只会留下成片 null。
 *
 * @author databus
 */
@Data
public class AgentComponentDraftSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 资源类型，固定 {@code COMPONENT_DRAFT}
     */
    private String resourceType;

    /**
     * 草稿组件主键（CREATE 为新行 id，UPDATE 为被改行 id）
     */
    private Long componentId;

    /**
     * CREATE 新建 / UPDATE 修改
     */
    private String operation;

    /**
     * 组件编码（仅定位与日志）
     */
    private String componentCode;

    /**
     * 组件名称（UPDATE 时为改前值）
     */
    private String componentName;

    /**
     * 台账五分类（UPDATE 时为改前值）
     */
    private String category;

    /**
     * 物料面板分组（UPDATE 时为改前值）
     */
    private String groupName;

    /**
     * 组件描述（UPDATE 时为改前值）
     */
    private String description;

    /**
     * 脚本正文（UPDATE 时为改前源码，回滚据此重走草稿保存）
     */
    private String scriptBody;

    /**
     * 试运行入参登记表（UPDATE 时为改前值）
     */
    private List<ChainInputParam> inputParams;

}