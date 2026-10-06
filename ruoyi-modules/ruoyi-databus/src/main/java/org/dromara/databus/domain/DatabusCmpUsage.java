package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 组件选择真账计数表 databus_cmp_usage。
 *
 * <p>前端每次在组件选择弹层选中一个组件即上报，按 (anchorType, pickedType, scene)
 * 三元组 upsert 累加 pick_count。推荐出分时以对数boost叠加在种子分之上。
 *
 * @author databus
 */
@Data
@TableName("databus_cmp_usage")
public class DatabusCmpUsage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 前置组件 def.type；无锚点记 ANY
     */
    private String anchorType;

    /**
     * 被选中组件 def.type
     */
    private String pickedType;

    /**
     * 插入场景（prepend/append/replace/insertEdge）
     */
    private String scene;

    /**
     * 累计被选中次数
     */
    private Integer pickCount;

    /**
     * 首次选中时间
     */
    private Date createTime;

    /**
     * 最近选中时间
     */
    private Date updateTime;

}
