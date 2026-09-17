package org.dromara.databus.component.data;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;

/**
 * 字段映射组件（注册名 {@code fieldMap}）。
 * <p>
 * 按 mappings 把上游字段逐条搬运到目标路径（平铺字段、原值复制，不做嵌套递归）。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("fieldMap")
public class FieldMapComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        FieldMapCfg cfg = this.getCmpData(FieldMapCfg.class);
        if (cfg == null || cfg.getMappings() == null || cfg.getMappings().isEmpty()) {
            throw new ServiceException("字段映射组件缺少 mappings 配置（tag=" + this.getTag() + "）");
        }
        int count = 0;
        for (FieldMapCfg.Mapping mapping : cfg.getMappings()) {
            if (mapping == null || mapping.getFrom() == null || mapping.getTo() == null) {
                throw new ServiceException("字段映射条目缺少 from/to（tag=" + this.getTag() + "）");
            }
            // 源路径不存在时取 null（允许把上游缺字段映射为空）
            Object value = getOptional(mapping.getFrom());
            save(mapping.getTo(), value);
            count++;
        }
        log.debug("[databus] fieldMap 完成 {} 条映射，tag={}", count, this.getTag());
    }
}
