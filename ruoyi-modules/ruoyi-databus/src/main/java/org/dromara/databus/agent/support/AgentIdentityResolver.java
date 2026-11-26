package org.dromara.databus.agent.support;

import com.aizuda.snail.ai.common.model.Result;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserAgentRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserRegisterRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserVO;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiAgentClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiUserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.config.properties.DatabusAgentProperties;
import org.dromara.system.api.model.LoginUser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static org.dromara.databus.constant.AgentConstants.SNAIL_AI_SUCCESS;

/**
 * 身份与身份材料：会话 id 规范化、权限点快照、SnailAI openId 的注册缓存与智能体订阅。
 * <p>
 * 只做身份解析与缓存，不建 run、不写消息、不判断会话状态。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentIdentityResolver {

    /**
     * 会话 id 白名单：只接受 UUID / 业务自生成的安全字符，拒绝把任意串塞进 SnailAI 会话（红线 15）。
     */
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final OpenApiUserClient userClient;
    private final OpenApiAgentClient agentClient;
    private final DatabusAgentProperties properties;

    /**
     * userId → openId 缓存（注册接口本身幂等，缓存只为省一次 HTTP）。
     */
    private final Map<Long, String> openIdCache = new ConcurrentHashMap<>();

    /**
     * 会话 id：为空则新开，非空必须是安全字符（拒绝任意串直传 SnailAI）。
     *
     * @param conversationId 前端传来的会话 id
     * @return 合法会话 id
     */
    public String resolveConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return UUID.randomUUID().toString();
        }
        if (!CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            throw new ServiceException("会话 id 不合法");
        }
        return conversationId;
    }

    /**
     * 权限点快照：菜单权限 + 角色权限（工具层据此鉴权，不接受入参里的身份）。
     *
     * @param loginUser 登录用户
     * @return 权限点列表
     */
    public List<String> snapshotPermissions(LoginUser loginUser) {
        List<String> permissions = new ArrayList<>();
        if (loginUser.getMenuPermission() != null) {
            permissions.addAll(loginUser.getMenuPermission());
        }
        if (loginUser.getRolePermission() != null) {
            permissions.addAll(loginUser.getRolePermission());
        }
        return permissions;
    }

    /**
     * 取 openId：注册接口幂等，按 userId 缓存。
     *
     * @param userId   用户 id
     * @param nickname 昵称
     * @return openId
     */
    public String resolveOpenId(Long userId, String nickname) {
        String cached = openIdCache.get(userId);
        if (cached != null) {
            return cached;
        }
        OpenApiUserRegisterRequest registerRequest = new OpenApiUserRegisterRequest();
        registerRequest.setExternalId(String.valueOf(userId));
        registerRequest.setNickname(nickname);
        Result<OpenApiUserVO> result = userClient.register(registerRequest);
        if (result == null || result.getStatus() != SNAIL_AI_SUCCESS
            || result.getData() == null || !StringUtils.hasText(result.getData().getOpenId())) {
            throw new ServiceException("注册 AI 用户失败：" + (result == null ? "返回为空" : result.getMessage()));
        }
        String openId = result.getData().getOpenId();
        subscribeAgent(openId);
        openIdCache.put(userId, openId);
        return openId;
    }

    /**
     * 确保 OpenAPI 用户已订阅配置的智能体。
     * <p>
     * SnailAI 对带 openId 的对话强制校验订阅关系（SubscriptionCheckHandler），
     * 未订阅会在流开始前就拒绝对话且不返回 done。订阅接口本身幂等，重复调用无副作用。
     *
     * @param openId SnailAI 用户标识
     */
    private void subscribeAgent(String openId) {
        OpenApiUserAgentRequest request = new OpenApiUserAgentRequest();
        request.setOpenId(openId);
        request.setAgentId(properties.getAgentId());
        try {
            Result<Void> result = agentClient.subscribeAgent(request);
            if (result == null || result.getStatus() != SNAIL_AI_SUCCESS) {
                log.warn("订阅智能体失败 openId={} agentId={} msg={}", openId, properties.getAgentId(),
                    result == null ? "返回为空" : result.getMessage());
            }
        } catch (Exception e) {
            log.warn("订阅智能体异常 openId={} agentId={}", openId, properties.getAgentId(), e);
        }
    }
}
