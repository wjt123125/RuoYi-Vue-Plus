package org.dromara.databus;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import org.dromara.databus.component.DatabusSmokeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据总线 LiteFlow 冒烟测试。
 * <p>
 * 对应阶段 0 验收标准第 5 条：验证组件注册、链路执行、异常捕获、日志打印。
 * 使用 {@link DatabusSmokeTestApplication} 隔离上下文，只装配 LiteFlow 三件套，
 * 不依赖数据库 / Redis / sa-token。
 *
 * @author databus
 */
@Tag("dev")
@DisplayName("数据总线 LiteFlow 冒烟测试")
@SpringBootTest(
    classes = DatabusSmokeTestApplication.class,
    properties = {
        "liteflow.enable=true",
        "liteflow.rule-source=classpath:liteflow/*.el.xml",
        // rule-db-sql 已进 classpath（生产走 Rule-DB），测试继续用本地规则文件需显式关掉 rule-db（官方逃生开关，二者互斥）
        "liteflow.rule-db.enabled=false"
    }
)
public class DatabusSmokeTest {

    @Autowired
    private FlowExecutor flowExecutor;

    /**
     * 验证 {@code THEN(hello, delay)} 链路执行成功，组件被正确注册并按顺序执行。
     */
    @DisplayName("THEN(hello, delay) 链路执行成功，组件注册与顺序执行符合预期")
    @Test
    public void smokeChainShouldExecuteInOrder() {
        DatabusSmokeContext context = new DatabusSmokeContext();

        LiteflowResponse response = flowExecutor.execute2Resp("databusSmokeChain", null, context);

        assertTrue(response.isSuccess(),
            () -> "链路应执行成功，实际: " + response.getMessage() + "，步骤: " + response.getExecuteStepStrWithTime());
        assertEquals(List.of("hello", "delay"), context.getTrace(),
            "上下文轨迹应按顺序记录 hello 与 delay");
    }

    /**
     * 验证 {@code THEN(hello, errorNode)} 中 errorNode 抛出的异常被 LiteFlow 捕获，
     * 链路标记为失败且异常原因可读。
     */
    @DisplayName("THEN(hello, errorNode) 抛出异常并被 LiteFlow 捕获")
    @Test
    public void errorChainShouldBeCaught() {
        DatabusSmokeContext context = new DatabusSmokeContext();

        LiteflowResponse response = flowExecutor.execute2Resp("databusErrorChain", null, context);

        assertFalse(response.isSuccess(), "异常链路应执行失败");
        Throwable cause = response.getCause();
        assertNotNull(cause, "异常原因不应为空");
        assertTrue(cause instanceof IllegalStateException,
            () -> "异常类型应为 IllegalStateException，实际: " + cause.getClass().getName());
        assertEquals(List.of("hello", "error"), context.getTrace(),
            "hello 应先执行，errorNode 抛异常前应已记录");
    }

}
