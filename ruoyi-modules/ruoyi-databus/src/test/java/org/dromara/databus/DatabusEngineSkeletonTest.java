package org.dromara.databus;

import com.jayway.jsonpath.TypeRef;
import org.dromara.databus.context.DatabusContext;
import org.dromara.databus.executor.DatabusExecutionResult;
import org.dromara.databus.executor.DatabusExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据总线执行引擎骨架测试。
 * <p>
 * 覆盖阶段 1B 交付物：
 * <ul>
 *     <li>{@link DatabusContext} JSONPath 读写、混合路径解析</li>
 *     <li>{@link org.dromara.databus.component.DatabusNodeComponent} 取参/写参</li>
 *     <li>{@link DatabusExecutor} 执行编排、节点步骤、上下文快照</li>
 *     <li>节点执行拦截器（日志埋点）</li>
 * </ul>
 * 使用 {@link DatabusSmokeTestApplication} 隔离上下文，只装配 LiteFlow 三件套，不依赖数据库 / Redis / sa-token。
 *
 * @author databus
 */
@Tag("dev")
@DisplayName("数据总线执行引擎骨架")
@SpringBootTest(
    classes = DatabusSmokeTestApplication.class,
    properties = {
        "liteflow.enable=true",
        "liteflow.rule-source=classpath:liteflow/*.el.xml"
    }
)
public class DatabusEngineSkeletonTest {

    @Autowired
    private DatabusExecutor databusExecutor;

    // ==================== DatabusContext 纯单元测试 ====================

    @DisplayName("DatabusContext：写入与读取简单路径")
    @Test
    public void contextShouldReadAndWrite() {
        DatabusContext ctx = DatabusContext.empty();
        ctx.write("$.user.name", "alice");
        assertEquals("alice", ctx.read("$.user.name"));
    }

    @DisplayName("DatabusContext：自动创建嵌套对象路径")
    @Test
    public void contextShouldAutoCreateNestedPath() {
        DatabusContext ctx = DatabusContext.empty();
        ctx.write("$.a.b.c.d", "deep");
        assertEquals("deep", ctx.read("$.a.b.c.d"));
    }

    @DisplayName("DatabusContext：readOptional 路径不存在返回默认值")
    @Test
    public void contextShouldReturnDefaultForMissingPath() {
        DatabusContext ctx = DatabusContext.empty();
        assertEquals("fallback", ctx.readOptional("$.missing", "fallback"));
    }

    @DisplayName("DatabusContext：混合路径解析替换 $.xxx 片段")
    @Test
    public void contextShouldResolveMixedPath() {
        DatabusContext ctx = DatabusContext.empty();
        ctx.write("$.user.name", "alice");
        ctx.write("$.user.age", 30);
        String result = ctx.resolveMixedPath("name=${$.user.name},age=${$.user.age}");
        assertEquals("name=alice,age=30", result);
    }

    @DisplayName("DatabusContext：resolve 统一入口支持纯路径与混合路径")
    @Test
    public void contextResolveShouldDispatchByType() {
        DatabusContext ctx = DatabusContext.empty();
        ctx.write("$.user.name", "alice");

        // 纯路径 → 读取
        assertEquals("alice", ctx.resolve("$.user.name"));
        // 混合路径 → 替换
        assertEquals("hi-alice", ctx.resolve("hi-${$.user.name}"));
        // 字面量 → 原样返回
        assertEquals(123, ctx.resolve(123));
    }

    @DisplayName("DatabusContext：数组写入与按索引读取")
    @Test
    public void contextShouldWriteAndReadArray() {
        DatabusContext ctx = DatabusContext.empty();
        ctx.write("$.items[0].id", 1);
        ctx.write("$.items[1].id", 2);
        List<Map<String, Object>> items = ctx.read("$.items", new TypeRef<List<Map<String, Object>>>() {
        });
        assertEquals(2, items.size());
        assertEquals(1, items.get(0).get("id"));
        assertEquals(2, items.get(1).get("id"));
    }

    // ==================== DatabusExecutor 集成测试 ====================

    @DisplayName("DatabusExecutor：成功链路返回正确结果与节点步骤")
    @Test
    public void executorShouldReturnSuccessWithSteps() {
        DatabusExecutionResult result = databusExecutor.execute("databusCtxChain", null);

        assertTrue(result.isSuccess(), () -> "链路应执行成功，错误: " + result.getMessage());
        assertNotNull(result.getExecutionId(), "executionId 不应为空");
        assertEquals("databusCtxChain", result.getChainId());
        assertEquals(2, result.getSteps().size(), "应有 2 个节点步骤");

        // 步骤顺序与状态
        assertEquals("ctxWrite", result.getSteps().get(0).getNodeId());
        assertTrue(result.getSteps().get(0).isSuccess());
        assertEquals("ctxRead", result.getSteps().get(1).getNodeId());
        assertTrue(result.getSteps().get(1).isSuccess());

        // 上下文快照应包含节点写入的数据
        String contextJson = result.getContextJson();
        assertNotNull(contextJson);
        assertTrue(contextJson.contains("hello-databus"), "上下文应包含 ctxWrite 写入的 greeting");
        assertTrue(contextJson.contains("greeting=hello-databus"), "上下文应包含 ctxRead 回写的混合路径结果");
    }

    @DisplayName("DatabusExecutor：失败链路返回失败结果与错误信息")
    @Test
    public void executorShouldReturnFailureForErrorChain() {
        DatabusExecutionResult result = databusExecutor.execute("databusCtxErrorChain", null);

        assertFalse(result.isSuccess(), "异常链路应执行失败");
        assertNotNull(result.getMessage(), "错误信息不应为空");

        // ctxWrite 成功，ctxError 失败
        assertEquals(2, result.getSteps().size());
        assertTrue(result.getSteps().get(0).isSuccess(), "ctxWrite 应成功");
        assertFalse(result.getSteps().get(1).isSuccess(), "ctxError 应失败");
        assertNotNull(result.getSteps().get(1).getErrorMessage());
    }

    @DisplayName("DatabusExecutor：请求数据被初始化为上下文根节点")
    @Test
    public void executorShouldInitContextFromRequestData() {
        Map<String, Object> request = Map.of("orderId", "ORD-001", "amount", 99.5);
        DatabusExecutionResult result = databusExecutor.execute("databusCtxChain", request);

        assertTrue(result.isSuccess());
        // 请求数据 + 节点写入的数据都应在上下文中
        String contextJson = result.getContextJson();
        assertTrue(contextJson.contains("ORD-001"), "上下文应包含请求数据 orderId");
        assertTrue(contextJson.contains("hello-databus"), "上下文应包含节点写入数据");
    }
}
