package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 组件推荐种子经验表 databus_cmp_recommend_seed。
 *
 * <p>上线前按组件语义人工编写的高置信前后置关系；anchor_type='ANY' / scene='any'
 * 分别为通用前置与全场景回退。仅作推荐基线，不参与任何运行时逻辑。
 *
 * @author databus
 */
@Data
@TableName("databus_cmp_recommend_seed")
public class DatabusCmpRecommendSeed implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 前置组件 def.type；ANY=通用基线
     */
    private String anchorType;

    /**
     * 被推荐组件 def.type
     */
    private String pickedType;

    /**
     * 插入场景（prepend/append/replace/insertEdge/any）
     */
    private String scene;

    /**
     * 种子分 0-100
     */
    private Integer score;

    /**
     * 打分理由（便于审阅）
     */
    private String reason;

    /**
     * 创建时间
     */
    private Date createTime;

}
