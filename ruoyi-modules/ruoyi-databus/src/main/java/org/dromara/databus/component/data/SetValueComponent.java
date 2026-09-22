package org.dromara.databus.component.data;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;

/**
 * 变量赋值组件（注册名 {@code setValue}）。
 * <p>
 * 把 value 按参数约定解析（字面量 / 裸路径整取 / 混合字符串替换）后写入 path。
 * <p>
 * 同时镜像一份到节点 tag 命名空间（{@code $.<tag>.path} 与 {@code $.<tag>.value}），
 * 遵循"每个节点产出在自己 tag 下"的统一契约，供 {@link
 * org.dromara.databus.executor.NodeStepResultCollector} 按统一规则采集
 * 数据明细快照；用户配置的 path 写入不受影响。
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

        // 镜像到 tag 命名空间，供 collector 按统一契约采集节点产出快照
        String tag = this.getTag();
        if (tag != null && !tag.isBlank()) {
            save("$." + tag + ".path", cfg.getPath());
            save("$." + tag + ".value", resolved);
        }

        resultSummary("赋值：" + cfg.getPath());
        log.debug("[databus] setValue 写入 path={}, tag={}", cfg.getPath(), this.getTag());
    }
}
