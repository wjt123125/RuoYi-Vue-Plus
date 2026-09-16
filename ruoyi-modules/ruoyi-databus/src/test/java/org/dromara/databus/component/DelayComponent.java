package org.dromara.databus.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * 冒烟测试：延时组件，模拟一个会消耗时间的普通业务节点。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("delay")
public class DelayComponent extends NodeComponent {

    @Override
    public void process() {
        DatabusSmokeContext context = getContextBean(DatabusSmokeContext.class);
        context.record("delay");
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("databus smoke: delay component executed");
    }

}
