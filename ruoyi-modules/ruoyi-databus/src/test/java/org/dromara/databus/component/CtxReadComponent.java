package org.dromara.databus.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行引擎骨架测试：从上下文读取上游写入的值，并回写到另一路径，验证节点间数据流转。
 * <p>
 * 验证 {@link DatabusNodeComponent#get(String)} / {@link #getOrDefault(String, Object)} 与混合路径解析。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("ctxRead")
public class CtxReadComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        String greeting = get("$.request.greeting");
        Integer count = getOrDefault("$.request.count", 0);
        // 混合路径解析：把上下文字段嵌入模板
        String echo = resolveParam("greeting=${$.request.greeting},count=${$.request.count}").toString();
        save("$.response.echo", echo);
        save("$.response.count", count);
        log.info("ctxRead: greeting={}, count={}, echo={}", greeting, count, echo);
    }
}
