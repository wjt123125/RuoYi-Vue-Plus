package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusComponent;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 组件元信息视图对象 databus_component
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusComponent.class)
public class DatabusComponentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 组件编码
     */
    private String componentCode;

    /**
     * 组件名称
     */
    private String componentName;

    /**
     * 物料网格短名
     */
    private String shortName;

    /**
     * 台账五分类
     */
    private String category;

    /**
     * 物料面板七组
     */
    private String groupName;

    /**
     * 业务叶子业务域（bpm/common，仅 business 组普通叶子使用）
     */
    private String domain;

    /**
     * 组件图标
     */
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
     * 脚本语言（第二步启用）
     */
    private String scriptLang;

    /**
     * 脚本正文（第二步启用）
     */
    private String scriptBody;

    /**
     * 脚本版本
     */
    private Integer version;

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
    private String deprecateNote;

    /**
     * 文档链接
     */
    private String docUrl;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
