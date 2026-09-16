package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 试运行结果视图对象。
 * <p>
 * 阶段 1A：仅返回 EL 生成结果与语法校验结果（不落库、不真执行）；
 * 阶段 1B 执行引擎落地后扩展执行状态、节点入参出参等字段。
 *
 * @author databus
 */
@Data
public class PreviewRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 由画布生成的 EL 表达式
     */
    private String elStr;

    /**
     * EL 是否校验通过
     */
    private Boolean valid;

    /**
     * 校验失败或生成异常时的提示信息（成功为 null）
     */
    private String message;

    public static PreviewRunVo ok(String elStr) {
        PreviewRunVo vo = new PreviewRunVo();
        vo.setElStr(elStr);
        vo.setValid(Boolean.TRUE);
        return vo;
    }

    public static PreviewRunVo fail(String elStr, String message) {
        PreviewRunVo vo = new PreviewRunVo();
        vo.setElStr(elStr);
        vo.setValid(Boolean.FALSE);
        vo.setMessage(message);
        return vo;
    }

}
