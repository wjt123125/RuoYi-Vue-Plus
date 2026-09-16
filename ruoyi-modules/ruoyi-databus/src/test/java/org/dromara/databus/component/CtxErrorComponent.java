package org.dromara.databus.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行引擎骨架测试：抛出异常，验证拦截器与执行结果的失败分支。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("ctxError")
public class CtxErrorComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        throw new IllegalStateException("ctxError: 主动抛出异常");
    }
}
