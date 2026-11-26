package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 助手用户可见错误的类别（设计选择，非厂商公开规范）。
 * <p>
 * 类别决定用户文案与重试策略；具体错误码只进日志/指标，不参与文案选择。
 * 六类划分的依据：公开文档支持“按错误类型区分处理 + 未知错误通用兜底”，
 * 具体归类粒度是本项目约定，见 docs/agent/error-handling.md。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AgentErrorCategoryEnum {

    /**
     * 限流、过载、超时、上游 5xx：可重试
     */
    TRANSIENT("transient"),

    /**
     * 需要用户改输入：上下文超长、参数不合法等
     */
    USER_ACTION("user_action"),

    /**
     * 部署/配置问题：模型未配置、密钥失效等，需联系管理员
     */
    CONFIG("config"),

    /**
     * 无权限或未订阅
     */
    PERMISSION("permission"),

    /**
     * 被内容策略拦截
     */
    CONTENT("content"),

    /**
     * 未识别
     */
    UNKNOWN("unknown");

    /**
     * 类别编码（下发前端，用于文案 key 与图标，不用于错误码映射）。
     */
    private final String code;

}
