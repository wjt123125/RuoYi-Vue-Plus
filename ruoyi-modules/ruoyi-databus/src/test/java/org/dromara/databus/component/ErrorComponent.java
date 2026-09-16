package org.dromara.databus.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * 冒烟测试：异常组件，主动抛出运行时异常，用于验证链路异常捕获。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("errorNode")
public class ErrorComponent extends NodeComponent {

    @Override
    public void process() {
        DatabusSmokeContext context = getContextBean(DatabusSmokeContext.class);
        context.record("error");
        log.info("databus smoke: error component will throw");
        throw new IllegalStateException("databus smoke: expected failure from errorNode");
    }

}
