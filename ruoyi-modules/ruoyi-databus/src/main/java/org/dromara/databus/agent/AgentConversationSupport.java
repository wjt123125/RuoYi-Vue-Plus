package org.dromara.databus.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.domain.DatabusAgentConversationMeta;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.vo.AgentApprovalModeVo;
import org.dromara.databus.mapper.DatabusAgentConversationMetaMapper;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 会话元数据：databus_agent_conversation_meta 的读写、会话归属校验与软删判定。
 * <p>
 * 只管会话级覆盖项（标题 / 置顶 / 软删 / 审批模式），不碰 run 状态机与 SSE。
 *
 * @author databus
 */
@Component
@RequiredArgsConstructor
public class AgentConversationSupport {

    private final DatabusAgentConversationMetaMapper metaMapper;
    private final DatabusAgentRunMapper runMapper;
    private final AgentApprovalService approvalService;

    /**
     * 重命名会话：写 / 覆盖 meta 的自定义标题。
     *
     * @param conversationId 会话 id
     * @param title          新标题
     */
    public void renameConversation(String conversationId, String title) {
        Long userId = requireOwnedConversation(conversationId);
        DatabusAgentConversationMeta meta = loadOrInitMeta(conversationId, userId);
        meta.setTitle(title.trim());
        saveMeta(meta);
    }

    /**
     * 置顶 / 取消置顶会话。
     *
     * @param conversationId 会话 id
     * @param pinned         true 置顶，false 取消
     */
    public void pinConversation(String conversationId, boolean pinned) {
        Long userId = requireOwnedConversation(conversationId);
        DatabusAgentConversationMeta meta = loadOrInitMeta(conversationId, userId);
        meta.setPinned(pinned ? "1" : "0");
        saveMeta(meta);
    }

    /**
     * 删除会话：只软删 meta 行，run / step / message 历史保留。
     *
     * @param conversationId 会话 id
     */
    public void deleteConversation(String conversationId) {
        Long userId = requireOwnedConversation(conversationId);
        DatabusAgentConversationMeta meta = loadOrInitMeta(conversationId, userId);
        meta.setDelFlag("1");
        saveMeta(meta);
    }

    /**
     * 撤销删除会话：把 meta 行的 del_flag 复位，历史原样回来。
     *
     * @param conversationId 会话 id
     */
    public void restoreConversation(String conversationId) {
        Long userId = requireOwnedConversation(conversationId);
        // 撤销删除只复位软删标记：run / step / message 一直没动，复位后列表自然回来
        DatabusAgentConversationMeta meta = loadOrInitMeta(conversationId, userId);
        meta.setDelFlag("0");
        saveMeta(meta);
    }

    /**
     * 读取会话级操作审批模式（无记录按 MANUAL，FULL 在部署未开放时归一为 MANUAL）。
     *
     * @param conversationId 会话 id
     * @return 生效模式与部署开关
     */
    public AgentApprovalModeVo getApprovalMode(String conversationId) {
        AgentApprovalModeVo vo = new AgentApprovalModeVo();
        vo.setMode(approvalService.getMode(conversationId, LoginHelper.getUserId()).getCode());
        vo.setAllowFullAccess(approvalService.isAllowFullAccess());
        return vo;
    }

    /**
     * 保存会话级操作审批模式。
     *
     * @param conversationId 会话 id
     * @param mode           模式编码 MANUAL/AUTO/FULL/CUSTOM
     */
    public void saveApprovalMode(String conversationId, String mode) {
        approvalService.saveMode(conversationId, LoginHelper.getUserId(), mode);
    }

    /**
     * 取会话的覆盖信息行，没有就构造一行待插入的（pinned / del_flag 给默认值）。
     * <p>
     * 不过滤 del_flag：软删过的会话再被操作时要找回同一行改写，否则会撞 conversation_id 唯一键。
     *
     * @param conversationId 会话 id
     * @param userId         归属用户
     * @return 已存在或待插入的 meta 行
     */
    private DatabusAgentConversationMeta loadOrInitMeta(String conversationId, Long userId) {
        DatabusAgentConversationMeta meta = metaMapper.selectOne(
            Wrappers.<DatabusAgentConversationMeta>lambdaQuery()
                .eq(DatabusAgentConversationMeta::getConversationId, conversationId));
        if (meta != null) {
            return meta;
        }
        meta = new DatabusAgentConversationMeta();
        meta.setConversationId(conversationId);
        meta.setUserId(userId);
        meta.setPinned("0");
        meta.setDelFlag("0");
        return meta;
    }

    /**
     * 落 meta：无主键插入，有主键按非空字段更新（null 字段不覆盖）。
     *
     * @param meta meta 行
     */
    private void saveMeta(DatabusAgentConversationMeta meta) {
        if (meta.getId() == null) {
            metaMapper.insert(meta);
        } else {
            metaMapper.updateById(meta);
        }
    }

    /**
     * 会话是否已被软删：meta 行不存在视为未删（从未改过标题/置顶/删除的会话没有 meta 行）。
     *
     * @param conversationId 会话 id
     * @return true 表示已软删，所有读接口都不该再返回它
     */
    public boolean isConversationDeleted(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        Long count = metaMapper.selectCount(Wrappers.<DatabusAgentConversationMeta>lambdaQuery()
            .eq(DatabusAgentConversationMeta::getConversationId, conversationId)
            .eq(DatabusAgentConversationMeta::getDelFlag, "1"));
        return count != null && count > 0;
    }

    /**
     * 校验会话归属当前用户（会话由 run 聚合而成，有 run 才算存在），返回当前用户 id。
     *
     * @param conversationId 会话 id
     * @return 当前用户 id
     */
    private Long requireOwnedConversation(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new ServiceException("会话不存在");
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            throw new ServiceException("当前登录用户为空");
        }
        Long count = runMapper.selectCount(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getUserId, userId)
            .eq(DatabusAgentRun::getConversationId, conversationId));
        if (count == null || count == 0) {
            throw new ServiceException("无权访问该会话");
        }
        return userId;
    }
}
