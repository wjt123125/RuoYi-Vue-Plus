package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusCmpRecommendSeed;

import java.util.List;

/**
 * 组件推荐种子经验 Mapper。
 *
 * @author databus
 */
public interface DatabusCmpRecommendSeedMapper extends BaseMapperPlus<DatabusCmpRecommendSeed, DatabusCmpRecommendSeed> {

    /**
     * 查某前置在某场景下命中的全部种子行：前置精确匹配 + ANY 通用基线，
     * 场景精确匹配 + any 全场景回退；同一 pickedType 可能命中多行，
     * 由 Service 按「精确前置优先于 ANY、精确场景优先于 any」取最高分。
     */
    @Select("""
        select id, anchor_type, picked_type, scene, score, reason, create_time
        from databus_cmp_recommend_seed
        where (anchor_type = #{anchorType} or anchor_type = 'ANY')
          and (scene = #{scene} or scene = 'any')
        """)
    List<DatabusCmpRecommendSeed> selectMatched(@Param("anchorType") String anchorType,
                                                @Param("scene") String scene);

}
