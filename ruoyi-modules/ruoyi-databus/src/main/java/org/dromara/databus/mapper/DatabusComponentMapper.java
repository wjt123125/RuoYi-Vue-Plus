package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.vo.DatabusComponentVo;

import java.util.Collection;

/**
 * 组件元信息 Mapper 接口
 *
 * @author databus
 */
public interface DatabusComponentMapper extends BaseMapperPlus<DatabusComponent, DatabusComponentVo> {

    /**
     * 物理删除（绕过 {@code @TableLogic} 逻辑删除）。
     * <p>
     * 唯一键 {@code uk_component_code} 只含 component_code 不含 del_flag，逻辑删留下的行会永久占码：
     * 应用层判重自动补 {@code del_flag='0'} 认为可建，DB 层却抛 Duplicate entry。
     * 因此「删了就再也不能用同码」的路径（草稿拒绝）必须真删。
     *
     * @param ids 主键集合
     * @return 实际删除行数
     */
    @Delete("<script>delete from databus_component where id in "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deletePhysicallyByIds(@Param("ids") Collection<Long> ids);
}