package org.dromara.databus.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusAgentConversationMeta;

/**
 * AI agent 会话覆盖信息 Mapper
 * <p>
 * 只做按 conversation_id 的增改查，无独立查询方法。
 *
 * @author databus
 */
public interface DatabusAgentConversationMetaMapper
    extends BaseMapperPlus<DatabusAgentConversationMeta, DatabusAgentConversationMeta> {

}