package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.util.List;

/**
 * 组件元信息对象 databus_component
 *
 * <p>列三语义（2026-10-06 立法，正本见 work-state 组件管理页段）：
 * 契约缓存（nodeType/editor/paramSchema/dataExample/inputSchema/outputSchema）、
 * 治理真身（名称/图标/分组/tags/废弃等）、脚本工件（scriptLang/scriptBody/version，第二步启用）。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "databus_component", autoResultMap = true)
public class DatabusComponent extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 组件编码（契约·身份，链路 cmp_property 节点 "id" 引用）
     */
    private String componentCode;

    /**
     * 组件名称【治理】
     */
    private String componentName;

    /**
     * 物料网格短名【治理】
     */
    private String shortName;

    /**
     * 台账五分类（PROTOCOL/DATA/FLOW_CONTROL/AI/PLATFORM）【治理】
     */
    private String category;

    /**
     * 物料面板七组（flow/sequence/branch/loop/other/subflow/business）【治理】
     */
    private String groupName;

    /**
     * 业务域（取值见 databus_component_domain 字典行；空＝未指派：business 普通叶子按字典 is_default 行归兜底域，node_type ≠ NODE 的槽件由后端派生为 slot）【治理】
     */
    private String domain;

    /**
     * 组件图标（svg 名或 Iconify 名）【治理】
     */
    private String icon;

    /**
     * 面板色值【治理】
     */
    private String color;

    /**
     * 面板排序（升序）【治理】
     */
    private Integer sort;

    /**
     * 组件描述【治理】
     */
    private String description;

    /**
     * 标签列表（tags 列 JSON 数组自动互转）【治理】
     */
    @TableField(value = "tags", typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /**
     * LiteFlow 节点类型（NODE/BOOLEAN/FOR/ITERATOR/SWITCH）【契约缓存】
     */
    private String nodeType;

    /**
     * 配置形态（form/script）【契约缓存】
     */
    private String editor;

    /**
     * 参数 schema 体（仅 {"fields":[...]}）【契约缓存】
     */
    private String paramSchema;

    /**
     * 配置 JSON 示例【契约缓存】
     */
    private String dataExample;

    /**
     * 输入 Schema（连线校验预留）【契约缓存】
     */
    private String inputSchema;

    /**
     * 输出 Schema（连线校验预留）【契约缓存】
     */
    private String outputSchema;

    /**
     * 脚本语言（第二步脚本宿主启用）【工件】
     */
    private String scriptLang;

    /**
     * 脚本正文（第二步脚本原子）【工件】
     */
    private String scriptBody;

    /**
     * 脚本版本【工件】
     */
    private Integer version;

    /**
     * 启停状态（0启用 1停用）【治理】
     */
    private String status;

    /**
     * 废弃标记（0正常 1废弃）【治理】
     */
    private String deprecated;

    /**
     * 废弃提示文案【治理】
     */
    private String deprecateNote;

    /**
     * 文档链接【治理】
     */
    private String docUrl;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

    /**
     * 备注（BaseEntity 不含 remark，表列自持）【治理】
     */
    private String remark;

}
