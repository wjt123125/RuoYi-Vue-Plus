package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据总线链路执行记录档位（databus_chain.log_level）
 * <p>
 * 挂字典 databus_log_level，挂 RuoYi 字典系统。三档语义见设计文档 §5.4：
 * <ul>
 *   <li>OFF 关闭：完全不落库，极致高吞吐场景</li>
 *   <li>BASIC 基础（默认）：执行级信息（入参/出参/状态/耗时/错误）</li>
 *   <li>FULL 完整：执行级 + 节点级每步 IO，关键链路/调试期</li>
 * </ul>
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum LogLevelEnum {

    /**
     * 关闭：完全不落库
     */
    OFF("OFF", "关闭"),

    /**
     * 基础：仅执行级信息（默认值）
     */
    BASIC("BASIC", "基础"),

    /**
     * 完整：执行级 + 节点级每步 IO
     */
    FULL("FULL", "完整");

    /**
     * 档位编码。
     */
    private final String code;

    /**
     * 档位说明。
     */
    private final String info;

    /**
     * 校验 code 是否为合法档位。
     *
     * @param code 待校验编码
     * @return 合法返回 true，null 或非枚举值返回 false
     */
    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        for (LogLevelEnum e : values()) {
            if (e.code.equals(code)) {
                return true;
            }
        }
        return false;
    }

}
