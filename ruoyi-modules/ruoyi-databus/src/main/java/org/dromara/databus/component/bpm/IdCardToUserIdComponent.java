package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.IdCardToUserIdRequest;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * IDCARD_TO_USERID 组件（注册名 {@code idCardToUserId}）。
 * <p>
 * 对应老系统 IdCardToUserIdProcessor：字段级组件，按配置的多个 path 逐个读取以
 * separator 分隔的身份证号字符串（输入拆分与输出拼接使用同一分隔符，默认逗号），
 * 调 BPM 端查 ORGUSER.EXT1 换 userId，结果原样写回同一路径。
 *
 * <p>失败语义（比老系统的静默 log 收紧）：某字段全部身份证号未匹配到用户时抛错，
 * 防止把空值写回业务字段；部分未匹配时告警并写回命中部分（与老系统 join 命中项行为一致）。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("idCardToUserId")
public class IdCardToUserIdComponent extends DatabusNodeComponent {

    private static final String DEFAULT_SEPARATOR = ",";

    @Override
    public void process() {
        IdCardToUserIdCfg cfg = this.getCmpData(IdCardToUserIdCfg.class);
        if (cfg == null) {
            throw new ServiceException("IDCARD_TO_USERID 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("IDCARD_TO_USERID 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("IDCARD_TO_USERID 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        if (cfg.getFields() == null || cfg.getFields().isEmpty()) {
            throw new ServiceException("IDCARD_TO_USERID 组件缺少 fields 配置（tag=" + tag + "）");
        }

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(cfg.getConnectionId());

        int converted = 0;
        int totalIdCards = 0;
        int totalMissed = 0;
        for (int i = 0; i < cfg.getFields().size(); i++) {
            IdCardToUserIdCfg.FieldCfg field = cfg.getFields().get(i);
            if (field == null || field.getPath() == null || field.getPath().isBlank()) {
                throw new ServiceException("IDCARD_TO_USERID 组件 fields[" + i + "].path 不能为空（tag=" + tag + "）");
            }
            String path = field.getPath();
            String raw = getOptional(path);
            if (raw == null || raw.isBlank()) {
                // 与老系统一致：值为空只告警跳过，不写回
                log.warn("[databus] idCardToUserId 路径 {} 身份证号为空，跳过 tag={}", path, tag);
                continue;
            }
            String separator = field.getSeparator() == null || field.getSeparator().isBlank()
                    ? DEFAULT_SEPARATOR : field.getSeparator();

            IdCardToUserIdRequest request = new IdCardToUserIdRequest();
            // 输入拆分与输出拼接必须用同一 separator（Pattern.quote 防 | ; 等正则元字符）；
            // 空段不过滤，由 BPM 端归入 missed，保持段语义与拼接端一致
            request.setIdCards(List.of(raw.split(Pattern.quote(separator))));
            request.setSeparator(separator);

            Object result = connector.idCardToUserId(conn, request);
            if (!(result instanceof Map<?, ?> resultMap)) {
                throw new ServiceException("IDCARD_TO_USERID 响应非 JSON 对象: " + result);
            }
            String userIds = resultMap.get("userIds") == null ? "" : String.valueOf(resultMap.get("userIds"));
            if (userIds.isEmpty()) {
                throw new ServiceException("IDCARD_TO_USERID 路径 " + path
                        + " 的全部身份证号均未匹配到 BPM 用户（tag=" + tag + "），原值: " + raw);
            }
            totalIdCards += request.getIdCards().size();
            Object missed = resultMap.get("missed");
            if (missed instanceof List<?> missedList && !missedList.isEmpty()) {
                totalMissed += missedList.size();
                log.warn("[databus] idCardToUserId 路径 {} 有 {} 个身份证号未匹配: {} tag={}",
                        path, missedList.size(), missedList, tag);
            }
            // 原地写回同一路径
            save(path, userIds);
            converted++;
        }
        String idCardSummary = totalIdCards + " 个身份证：命中 " + (totalIdCards - totalMissed)
                + "，未命中 " + totalMissed;
        if (converted > 1) {
            idCardSummary += "（" + converted + " 个字段）";
        }
        resultSummary(idCardSummary);
        log.info("[databus] idCardToUserId 完成 tag={} 转换字段数={}", tag, converted);
    }
}
