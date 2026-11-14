package org.dromara.databus.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusAgentApprovalSetting;

/**
 * AI 会话级操作审批模式 Mapper
 * <p>
 * 只做按 conversation_id 的增改查，无独立查询方法。
 *
 * @author databus
 */
public interface DatabusAgentApprovalSettingMapper
    extends BaseMapperPlus<DatabusAgentApprovalSetting, DatabusAgentApprovalSetting> {

}