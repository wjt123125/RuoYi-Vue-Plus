package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.el.bean.ChainInputParam;
import org.dromara.databus.el.bean.CmpProperty;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

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
     * 创建人 id（前端据此对非本人链路置画布只读态；系统资源 owner_type=SYSTEM 时 create_by 为空，
     * 编辑权限由服务端按 owner_type 判定，非仅靠 create_by）
     */
    private Long createBy;

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
     * 状态（DRAFT 草稿 / ENABLED 已发布 / DISABLED 已下线）
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
     * 链路入参登记表（编辑器据此渲染登记弹窗、试运行预填默认值）
     */
    private List<ChainInputParam> inputParams;

    /**
     * 归属类型（SYSTEM 系统资源 / USER 用户资源；AI 写工具据此区分「系统链路只能治理角色改」）
     */
    private String ownerType;

    /**
     * 是否精选模板（0否 1是）
     */
    private String isTemplate;

    /**
     * 模板说明（模板库卡片展示）
     */
    private String templateDesc;

    /**
     * 模板排序（升序，值小在前）
     */
    private Integer templateSort;

    /**
     * 备注
     */
    private String remark;

    /**
     * 所属目录id（null=未归组，工作台树据此归组渲染）
     */
    private Long directoryId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
