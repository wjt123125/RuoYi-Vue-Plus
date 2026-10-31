package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组件↔连接器依赖关系 databus_component_connector_dep（material-market §15.12）。
 *
 * <p>存在的唯一理由是**让「停用/卸载某连接器会影响哪些组件」这条反查走索引**：组件对连接器的依赖
 * 本来已经在 {@code databus_component.param_schema} 里（{@code PropSchema.connectorFamily}），
 * 但那是 JSON 文本，反查要把全库组件读出来逐个解析，产品规模下不可接受（§15.12.2）。
 * 因此本表是 param_schema 的**派生索引**，由 {@code DatabusComponentServiceImpl} 在四个写入点
 * 同步维护（§15.12.7），不作为独立事实源——两者冲突时以 param_schema 为准。
 *
 * <p>列名 {@code connectorFamily} 存的是**能力族**不是具体 type：{@code jdbc} 覆盖
 * {@code jdbc-postgresql} / {@code jdbc-oracle} …，{@code bpmHttp} 是单成员族。组件只声明族，
 * 具体连哪个库由连接行 {@code sys_databus_connection.connector_type} 决定，
 * 故依赖关系天然只能落在族这一级（理由全文见 script/sql/databus_component_connector_dep.sql 文件头）。
 *
 * <p>不继承 {@code BaseEntity}：本表只有四列，无 create_by / update_time 等审计列
 * （依赖行由组件保存动作派生，审计信息看组件本身与 databus_component_version 即可），
 * 沿用 {@code SysUserRole} 这类关联表的写法。
 *
 * @author databus
 */
@Data
@TableName("databus_component_connector_dep")
public class DatabusComponentConnectorDep {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 组件编码（对应 databus_component.component_code）
     */
    private String componentCode;

    /**
     * 连接器能力族（对应 ConnectorDescriptor.family，如 jdbc / bpmHttp）
     */
    private String connectorFamily;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
