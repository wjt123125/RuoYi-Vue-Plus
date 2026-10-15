package org.dromara.databus.alert;

import cn.hutool.core.lang.Dict;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mail.core.MailBuilder;
import org.dromara.databus.enums.AlertNotifyStatusEnum;
import org.dromara.databus.enums.AlertNotifyTypeEnum;
import org.springframework.stereotype.Component;

/**
 * 邮件通知渠道（ruoyi-common-mail）。
 * <p>
 * 渠道参数：{@code {"emails":"a@x.com,b@x.com"}}。邮件通道未启用（mail.enabled=false）
 * 或 SMTP 异常时 {@code MailBuilder.send()} 抛异常，由路由记为该渠道 FAILED 留痕。
 * 触发与恢复共用同一模板，主题与正文按事件状态区分。
 *
 * @author databus
 */
@Slf4j
@Component
public class EmailAlertNotifier implements AlertNotifier {

    @Override
    public AlertNotifyTypeEnum type() {
        return AlertNotifyTypeEnum.EMAIL;
    }

    @Override
    public String send(AlertEvent event, String configJson) throws Exception {
        Dict config = parseConfig(configJson);
        String emails = config.getStr("emails");
        if (StringUtils.isBlank(emails)) {
            throw new IllegalArgumentException("邮件渠道缺少 emails 参数");
        }
        MailBuilder.of()
            .to(emails)
            .subject(buildSubject(event))
            .text(buildText(event))
            .send();
        return AlertNotifyStatusEnum.SUCCESS.getCode();
    }

    private Dict parseConfig(String configJson) {
        Dict config = StringUtils.isBlank(configJson) ? null : JsonUtils.parseMap(configJson);
        return config == null ? new Dict() : config;
    }

    private String buildSubject(AlertEvent event) {
        String prefix = event.isRecovered() ? "【数据总线恢复】" : "【数据总线告警】";
        return prefix + event.getRuleName();
    }

    private String buildText(AlertEvent event) {
        StringBuilder sb = new StringBuilder()
            .append("数据总线链路").append(event.isRecovered() ? "恢复通知" : "告警通知").append('\n')
            .append("事件状态：").append(event.getStatusInfo()).append('\n')
            .append("规则名称：").append(event.getRuleName()).append('\n')
            .append("目标链路：").append(event.getChainCode()).append('\n')
            .append("规则类型：").append(event.getAlertTypeInfo()).append('\n')
            .append("评估窗口：最近 ").append(event.getWindowMinutes()).append(" 分钟\n")
            .append("事件说明：").append(event.getMessage()).append('\n')
            .append("触发时间：").append(event.getTriggerTime()).append('\n');
        if (event.isRecovered()) {
            sb.append("恢复时间：").append(event.getRecoverTime()).append('\n');
        }
        if (event.getThresholdCount() != null) {
            sb.append("连续失败阈值：").append(event.getThresholdCount()).append(" 次\n");
        }
        if (event.getThresholdRate() != null) {
            sb.append("成功率下限：").append(event.getThresholdRate()).append("%\n");
        }
        sb.append("\n请到数据总线告警管理页查看详情。\n");
        return sb.toString();
    }

}
