package org.dromara.databus.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行引擎骨架测试：向上下文写入一个固定值，供下游节点读取。
 * <p>
 * 验证 {@link DatabusNodeComponent#save(String, Object)} 与 {@link DatabusContext#write} 的自动建路径能力。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("ctxWrite")
public class CtxWriteComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        save("$.request.greeting", "hello-databus");
        save("$.request.count", 42);
        log.info("ctxWrite: 已写入 $.request.greeting 与 $.request.count");
    }
}
