package org.dromara.databus.domain.bo;

import lombok.Data;
import org.dromara.databus.el.bean.CmpProperty;

import java.io.Serial;
import java.io.Serializable;

/**
 * 试运行请求：当前画布组件树 + 执行入参 JSON。
 *
 * @author databus
 */
@Data
public class PreviewRunBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 画布组件树（由后端生成 EL）。
     */
    private CmpProperty jsonEl;

    /**
     * 执行入参 JSON 字符串，解析后作为上下文文档根（{@code $}）；为空时上下文为空文档。
     */
    private String requestJson;
}
