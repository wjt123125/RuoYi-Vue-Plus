package org.dromara.databus.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.SysDatabusConnection;
import org.dromara.databus.domain.vo.SysDatabusConnectionVo;

/**
 * 数据总线连接管理 Mapper 接口。
 *
 * <p>沿用 RuoYi-Vue-Plus 范式：{@link BaseMapperPlus} 已提供 selectVoById /
 * selectVoPage / insert / updateById / deleteByIds 等全部 CRUD 能力，无需 xml。
 *
 * @author databus
 */
public interface SysDatabusConnectionMapper extends BaseMapperPlus<SysDatabusConnection, SysDatabusConnectionVo> {

}
