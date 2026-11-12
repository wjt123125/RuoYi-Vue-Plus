package org.dromara.databus.agent;

import java.util.List;

/**
 * 工具执行时的身份快照（方案 §十五 守卫 ②）。
 * <p>
 * 全部字段来自 {@code databus_agent_run}（conversationId 反查所得），
 * 不读取模型入参里的任何身份字段，也不依赖 Sa-Token 登录态。
 *
 * @param runId          本轮 run 句柄
 * @param conversationId SnailAI 会话 id
 * @param userId         发起人（写操作据此显式 setCreateBy）
 * @param chainId        绑定的链路 id，可为空（从零建）
 * @param permissions    发起时的权限点快照
 * @param round          已用纠错轮数
 * @author databus
 */
public record AgentRunContext(String runId,
                              String conversationId,
                              Long userId,
                              Long chainId,
                              List<String> permissions,
                              Integer round) {

    /**
     * RuoYi 超级管理员通配权限点。
     */
    private static final String PERMISSION_ALL = "*:*:*";

    public AgentRunContext {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    /**
     * 权限点是否在发起时的快照内（超管按 {@code *:*:*} 通配）。
     *
     * @param permission 所需权限点，空表示不校验
     * @return 是否放行
     */
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return permissions.contains(PERMISSION_ALL) || permissions.contains(permission);
    }

}