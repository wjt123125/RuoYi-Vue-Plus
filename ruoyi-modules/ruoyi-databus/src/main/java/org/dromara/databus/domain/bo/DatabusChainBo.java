package org.dromara.databus.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.el.bean.CmpProperty;

import java.io.Serial;
import java.io.Serializable;

/**
 * 链路定义业务对象 databus_chain。
 * <p>
 * 保存时由前端提交画布 JSON（{@link #canvasData}，VueFlow nodes/edges 序列化串）
 * 与逻辑组件树（{@link #cmpProperty}）；EL 表达式由后端调用 ExpressGenerator
 * 从组件树权威生成，不接受前端传入的 EL，避免画布与 EL 不一致。
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusChain.class, reverseConvertGenerate = false)
public class DatabusChainBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id（新增为空，编辑必填）
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 链路编码
     */
    @NotBlank(message = "链路编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "链路编码长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String chainCode;

    /**
     * 链路名称
     */
    @NotBlank(message = "链路名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "链路名称长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String chainName;

    /**
     * 画布 JSON（VueFlow nodes/edges 序列化串，编辑器还原用）
     */
    private String canvasData;

    /**
     * 画布逻辑组件树（后端据此生成 EL 表达式）
     */
    private CmpProperty cmpProperty;

    /**
     * 执行记录档位（OFF/BASIC/FULL，默认 BASIC）
     * <p>
     * 草稿阶段可配置；发布后下线再重新发布时仍可调整。
     */
    private String logLevel;

    /**
     * 备注
     */
    private String remark;

}
