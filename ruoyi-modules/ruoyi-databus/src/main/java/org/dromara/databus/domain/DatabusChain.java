package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 链路定义对象 databus_chain
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_chain")
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
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

    /**
     * 备注（BaseEntity 不含 remark，表列自持）
     */
    private String remark;

}
