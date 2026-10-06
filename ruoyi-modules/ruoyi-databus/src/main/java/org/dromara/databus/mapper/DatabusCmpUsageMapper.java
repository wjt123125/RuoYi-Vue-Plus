package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusCmpUsage;

import java.util.List;

/**
 * 组件选择真账计数 Mapper。
 *
 * @author databus
 */
public interface DatabusCmpUsageMapper extends BaseMapperPlus<DatabusCmpUsage, DatabusCmpUsage> {

    /**
     * 选中计数 +1：三元组不存在则插入，存在则原子累加（避免先查后写的并发竞态）。
     * id 由 Service 用雪花算法生成，仅插入分支使用。
     */
    @Insert("""
        insert into databus_cmp_usage (id, anchor_type, picked_type, scene, pick_count, create_time, update_time)
        values (#{id}, #{anchorType}, #{pickedType}, #{scene}, 1, now(), now())
        on duplicate key update pick_count = pick_count + 1, update_time = now()
        """)
    int upsertCount(@Param("id") Long id,
                    @Param("anchorType") String anchorType,
                    @Param("pickedType") String pickedType,
                    @Param("scene") String scene);

    /**
     * 查某前置在某场景下的全部后继计数（供推荐融合）。
     */
    @Select("""
        select id, anchor_type, picked_type, scene, pick_count, create_time, update_time
        from databus_cmp_usage
        where anchor_type = #{anchorType} and scene = #{scene}
        """)
    List<DatabusCmpUsage> selectByAnchor(@Param("anchorType") String anchorType,
                                         @Param("scene") String scene);

}
