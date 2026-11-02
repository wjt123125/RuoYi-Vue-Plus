package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据总线组件状态（databus_component.status）
 * <ul>
 *   <li>DRAFT 草稿：脚本已落库但未生效，不注册进 FlowBus、不进组件面板</li>
 *   <li>ENABLED 启用：可被编辑器引用、启动时注册</li>
 *   <li>DISABLED 停用：不进组件面板，存量已发布链路继续运行</li>
 * </ul>
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum ComponentStatusEnum {

    /**
     * 草稿
     */
    DRAFT("DRAFT", "草稿"),

    /**
     * 启用
     */
    ENABLED("ENABLED", "启用"),

    /**
     * 停用
     */
    DISABLED("DISABLED", "停用");

    /**
     * 状态编码。
     */
    private final String code;

    /**
     * 状态说明。
     */
    private final String info;

}