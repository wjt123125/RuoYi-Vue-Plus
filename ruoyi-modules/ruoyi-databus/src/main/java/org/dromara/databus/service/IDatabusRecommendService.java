package org.dromara.databus.service;

import org.dromara.databus.domain.bo.CmpPickBo;
import org.dromara.databus.domain.vo.CmpRecommendVo;

import java.util.List;

/**
 * 组件插入推荐服务。
 *
 * <p>数据两路：种子经验表（人工编写的基线）+ 真账计数表（用户真实选择），
 * 出分时种子给基线、真账按对数函数抬分（封顶 60），随使用积累平滑交接主导权。
 *
 * @author databus
 */
public interface IDatabusRecommendService {

    /**
     * 按场景与前置组件取有序推荐（分数降序）。
     *
     * @param mode          插入场景（prepend/append/replace/insertEdge）
     * @param anchorType    前置组件 def.type，无锚点传 null
     * @param excludedTypes 需排除的类型（已存在 singleton、replace 时的自身）
     * @return 有信号的推荐项（未列出的类型由前端按本地规则兜底）
     */
    List<CmpRecommendVo> recommend(String mode, String anchorType, List<String> excludedTypes);

    /**
     * 上报一次真实选择（三元组计数 +1）。
     */
    void recordPick(CmpPickBo bo);

}
