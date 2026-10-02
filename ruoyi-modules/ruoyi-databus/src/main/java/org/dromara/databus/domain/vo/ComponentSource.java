package org.dromara.databus.domain.vo;

/**
 * 物料来源：内置（注解注册）/ 自定义（databus_component 表）。
 *
 * @author databus
 */
public enum ComponentSource {

    /**
     * 内置物料：@DatabusCmp 注解 + 启动扫描注册，code 冲突时优先于自定义行。
     */
    SYSTEM,

    /**
     * 自定义物料：databus_component 表启用行，param_schema 为同构 JSON。
     */
    CUSTOM
}
