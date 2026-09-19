package org.dromara.databus.component.data;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;

/**
 * 变量赋值组件（注册名 {@code setValue}）。
 * <p>
 * 把 value 按参数约定解析（字面量 / 裸路径整取 / 混合字符串替换）后写入 path。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("setValue")
public class SetValueComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        SetValueCfg cfg = this.getCmpData(SetValueCfg.class);
        if (cfg == null || cfg.getPath() == null || cfg.getPath().isBlank()) {
            throw new ServiceException("赋值组件缺少 path 配置（tag=" + this.getTag() + "）");
        }
        Object resolved = resolveParam(cfg.getValue());
        save(cfg.getPath(), resolved);
        resultSummary("赋值：" + cfg.getPath());
        log.debug("[databus] setValue 写入 path={}, tag={}", cfg.getPath(), this.getTag());
    }
}
