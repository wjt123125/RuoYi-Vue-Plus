package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.el.bean.CmpProperty;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 链路定义视图对象 databus_chain
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusChain.class)
public class DatabusChainVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
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
     * 版本号
     */
    private Integer version;

    /**
     * 状态（0草稿 1已发布 2已下线）
     */
    private String status;

    /**
     * LiteFlow EL 表达式
     */
    private String elExpression;

    /**
     * 画布 JSON（VueFlow nodes/edges 序列化串）
     */
    private String canvasData;

    /**
     * 画布逻辑组件树（与 Bo/实体同名同类型；编辑器直接消费，无需二次解析）
     */
    private CmpProperty cmpProperty;

    /**
     * 执行记录档位（OFF/BASIC/FULL，默认 BASIC）
     */
    private String logLevel;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
