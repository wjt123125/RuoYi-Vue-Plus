package org.dromara.databus.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * 冒烟测试：Hello 组件，链路入口节点，打印日志并在上下文记录轨迹。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("hello")
public class HelloComponent extends NodeComponent {

    @Override
    public void process() {
        DatabusSmokeContext context = getContextBean(DatabusSmokeContext.class);
        context.record("hello");
        log.info("databus smoke: hello component executed");
    }

}
