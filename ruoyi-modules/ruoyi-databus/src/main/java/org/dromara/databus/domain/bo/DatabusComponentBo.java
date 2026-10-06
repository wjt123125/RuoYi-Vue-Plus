package org.dromara.databus.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.databus.domain.DatabusComponent;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 组件元信息业务对象 databus_component
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusComponent.class, reverseConvertGenerate = false)
public class DatabusComponentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 组件编码
     */
    @NotBlank(message = "组件编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "组件编码长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String componentCode;

    /**
     * 组件名称
     */
    @NotBlank(message = "组件名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "组件名称长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String componentName;

    /**
     * 物料网格短名
     */
    @Size(max = 50, message = "短名长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String shortName;

    /**
     * 台账五分类
     */
    @NotBlank(message = "组件分类不能为空", groups = {AddGroup.class, EditGroup.class})
    private String category;

    /**
     * 物料面板七组
     */
    private String groupName;

    /**
     * 业务叶子业务域（bpm/common，仅 business 组普通叶子使用）
     */
    @Size(max = 16, message = "业务域长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String domain;

    /**
     * 组件图标
     */
    @Size(max = 100, message = "图标长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String icon;

    /**
     * 面板色值
     */
    private String color;

    /**
     * 面板排序
     */
    private Integer sort;

    /**
     * 组件描述
     */
    @Size(max = 500, message = "组件描述长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * LiteFlow 节点类型
     */
    private String nodeType;

    /**
     * 配置形态（form/script）
     */
    private String editor;

    /**
     * 参数 schema 体（仅 {"fields":[...]}）
     */
    private String paramSchema;

    /**
     * 配置 JSON 示例
     */
    private String dataExample;

    /**
     * 输入 Schema
     */
    private String inputSchema;

    /**
     * 输出 Schema
     */
    private String outputSchema;

    /**
     * 启停状态（0启用 1停用）
     */
    private String status;

    /**
     * 废弃标记（0正常 1废弃）
     */
    private String deprecated;

    /**
     * 废弃提示文案
     */
    @Size(max = 200, message = "废弃说明长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String deprecateNote;

    /**
     * 文档链接
     */
    @Size(max = 255, message = "文档链接长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String docUrl;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

}
