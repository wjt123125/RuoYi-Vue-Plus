package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.databus.el.bean.CmpProperty;

/**
 * 链路定义对象 databus_chain
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "databus_chain", autoResultMap = true)
public class DatabusChain extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 链路编码
     */
    private String chainCode;

    /**
     * 链路名称
     */
    private String chainName;

    /**
     * 版本号（每次发布递增，草稿阶段恒为 1）
     */
    private Integer version;

    /**
     * 状态（0草稿 1已发布 2已下线）
     */
    private String status;

    /**
     * LiteFlow EL 表达式（执行引擎用，由后端从 CmpProperty 权威生成）
     */
    private String elExpression;

    /**
     * 画布 JSON（VueFlow nodes/edges 序列化，编辑器还原用）
     */
    private String canvasData;

    /**
     * 画布逻辑组件树（EL 权威源的输入；与 Bo/Vo 同名同类型）
     * <p>
     * 通过 JacksonTypeHandler 与 cmp_property 列的 JSON 文本自动互转；
     * {@code @TableName} 上必须配 autoResultMap = true，查询结果才会走 typeHandler 反序列化。
     * 发布时据此提取脚本节点（script/booleanScript）推送到 Rule-DB 的 lf_script 表。
     */
    @TableField(value = "cmp_property", typeHandler = JacksonTypeHandler.class)
    private CmpProperty cmpProperty;

    /**
     * 执行记录档位（OFF/BASIC/FULL，默认 BASIC）
     * <p>
     * OFF 不落库；BASIC 仅执行级信息（入参/出参/状态/耗时/错误）；
     * FULL 执行级 + 节点级每步 IO。挂字典 databus_log_level。
     */
    private String logLevel;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

    /**
     * 备注（BaseEntity 不含 remark，表列自持）
     */
    private String remark;

}
