package org.dromara.databus.el.bean;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 链路入参登记表条目（2026-09-27 拍板，见 databus-context-design.md §3.4）。
 * <p>
 * 每条登记声明一个「执行时需从外部传入的值」：路径为完整 JsonPath（{@code $.} 开头），
 * 类型仅作标记（文本/数字/布尔/对象/数组，本次不做类型校验），默认值只服务人工试运行预填，
 * 必填在试运行与真实执行前统一校验。
 *
 * @author databus
 */
@Data
public class ChainInputParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 入参路径（完整 JsonPath，如 $.request.password；UI 上 $. 为固定前缀）
     */
    private String path;

    /**
     * 类型标记：text/number/boolean/object/array（仅存储，不做类型校验）
     */
    private String type;

    /**
     * 默认值（试运行打开时预填；无默认留空。真实执行不注入）
     */
    private Object defaultValue;

    /**
     * 是否必填（true 时执行前按路径取值，取不到/空字符串即拦截）
     */
    private Boolean required;

}
