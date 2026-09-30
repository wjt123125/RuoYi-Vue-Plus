package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 链路「设为精选模板」业务对象。
 * <p>
 * 仅服务模板标记端点（POST /databus/chain/template/{id}），与通用链路保存隔离：
 * 模板说明必填、面向使用者（适用场景/前置条件），排序控制模板库内露出顺序。
 *
 * @author databus
 */
@Data
public class TemplateMarkBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模板说明（模板库卡片主文案：适用场景、前置条件、能学到什么）
     */
    @NotBlank(message = "模板说明不能为空")
    @Size(max = 500, message = "模板说明长度不能超过{max}个字符")
    private String templateDesc;

    /**
     * 模板排序（升序，值小在前；空值按默认 0 落库）
     */
    private Integer templateSort;

}
