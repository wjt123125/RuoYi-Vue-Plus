package org.dromara.databus.domain.bo;

import lombok.Data;
import org.dromara.databus.el.bean.ChainInputParam;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 脚本草稿落库入参（AI 造组件专用 {@code databus_component_draft_save}）。
 * <p>
 * 与 {@link ScriptSaveBo} 的区别：本入参走「先落草稿、人点采纳」的路子——只编译建草稿行，
 * 不注册 FlowBus、不进物料面板；采纳动作由 {@code /script/draft/adopt} 完成。
 * 草稿行的 create_by 由服务端按会话身份注入，不接受前端传入。
 *
 * @author databus
 */
@Data
public class ScriptDraftBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 草稿组件主键；为空＝新建草稿，有值＝更新自己名下的草稿
     */
    private Long id;

    /**
     * 组件编码（新建必填；更新时以库中编码为准）
     */
    private String componentCode;

    /**
     * 组件名称（新建必填）
     */
    private String componentName;

    /**
     * 台账五分类 PROTOCOL/DATA/FLOW_CONTROL/AI/PLATFORM，新建缺省 PLATFORM
     */
    private String category;

    /**
     * 物料面板七组（flow/sequence/branch/loop/other/subflow/business），可空
     */
    private String groupName;

    /**
     * 组件描述
     */
    private String description;

    /**
     * 脚本正文（完整 Java 类源码，含 @DatabusCmp 主类与内嵌 Cfg）
     */
    private String scriptBody;

    /**
     * 试运行入参登记表：AI 已在探针里跑通的样板入参（路径/类型/默认值/必填）。
     * <p>
     * 存到 databus_component.input_params，预览链路的试运行据此预填入参。
     * 为空表示不登记（预览时入参就是 {}）。
     */
    private List<ChainInputParam> inputParams;

    /**
     * 备注（AI 可写危险 API 自查说明）
     */
    private String remark;

    /**
     * 发起人（会话身份，服务端注入，不接受前端参数）
     */
    private Long createBy;

}