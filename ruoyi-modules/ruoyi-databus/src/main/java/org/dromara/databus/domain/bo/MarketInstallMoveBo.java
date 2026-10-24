package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 安装记录目录归属移动对象（引用挂树：工作区资源树拖拽/移动 SHARED 组件引用叶子用，§十二-7）
 * <p>
 * 只更新 install 行的 directory_id（组织视角），绝不动源组件行的 directory_id。
 *
 * @author databus
 */
@Data
public class MarketInstallMoveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 安装记录主键
     */
    @NotNull(message = "安装记录主键不能为空")
    private Long installId;

    /**
     * 目标目录id（空=移出到未归组）
     */
    private Long directoryId;

}
