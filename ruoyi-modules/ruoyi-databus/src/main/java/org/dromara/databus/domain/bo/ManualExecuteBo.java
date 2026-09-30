package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 手动执行请求业务对象（设计档 §4.5）。
 * <p>
 * 选已发布链路 + 提交执行入参 JSON，走正式 {@code execute()} 通道，
 * 按链路 log_level 落执行记录。重跑不使用本 Bo——直接取历史记录的 request_data。
 *
 * @author databus
 */
@Data
public class ManualExecuteBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 链路主键（必须为已发布状态，非发布态在 Service 层拦截）
     */
    @NotNull(message = "链路id不能为空")
    private Long chainId;

    /**
     * 执行入参 JSON 字符串，解析后作为上下文文档根（{@code $}）；为空时上下文为空文档。
     */
    private String requestJson;

}
