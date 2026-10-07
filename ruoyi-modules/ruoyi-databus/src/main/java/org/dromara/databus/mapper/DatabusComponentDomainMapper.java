package org.dromara.databus.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusComponentDomain;
import org.dromara.databus.domain.vo.ComponentDomainVo;

/**
 * 物料业务域字典 Mapper 接口。
 *
 * <p>{@link BaseMapperPlus} 已提供 selectList / selectVoList / insert / updateById /
 * deleteByIds 等全部 CRUD 能力，无需 xml。
 *
 * <p>注意：字典查询走 selectList + 服务层手动装配 {@link ComponentDomainVo}，
 * 不用 selectVoList——VO 字段名（key/label/isDefault）与实体属性名
 * （domainKey/domainName/isDefault 的 Y|N 字符）语义不一致，mapstruct-plus 的属性名映射拷不上。
 *
 * @author databus
 */
public interface DatabusComponentDomainMapper extends BaseMapperPlus<DatabusComponentDomain, ComponentDomainVo> {

}
