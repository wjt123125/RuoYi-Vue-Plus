package org.dromara.databus;

import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.context.JsonCodec;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.executor.DatabusExecutionResult;
import org.dromara.databus.executor.DatabusExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHEN 并行链路端到端压测（2026-09-27 DatabusContext 读写锁修复验收用例）。
 * <p>
 * 与 {@link org.dromara.databus.context.DatabusContextConcurrencyTest} 的纯锤测互补：
 * 本类走<b>真实生产执行路径</b>——ExpressGenerator 从画布树生成 EL、真实 {@code setValue}
 * 组件在 LiteFlow WHEN 线程池里并行执行、{@code NodeStepResultCollector} 生命周期钩子
 * 在各分支工作线程并发拍摄数据空间快照（读锁）与其他分支写入（写锁）真实交错。
 * <p>
 * 画布结构与种子链 {@code when-parallel} 同构，仅把 3 路扩到 20 路：
 * <pre>
 * WHEN(
 *   setValue.tag("setValue1").data({"path":"$.setValue1.out","value":"并行 1"}),
 *   ... 共 20 个分支 ...
 * )
 * </pre>
 * 验收口径：连续执行 <b>500</b> 次，每次 20 个分支全部成功、步骤数 20、
 * 最终上下文 20 个 key 零丢失、全程无 {@link java.util.ConcurrentModificationException}。
 * <p>
 * 标签为 {@code stress}：不进 dev 常规测试组（避免每次构建多跑约一分钟），
 * 在 IDE 中直接运行本类，或命令行：
 * <pre>
 * mvnw -pl ruoyi-modules/ruoyi-databus -am test -Dmaven.test.skip=false -Dgroups=stress
 * </pre>
 *
 * @author databus
 */
@Tag("stress")
@DisplayName("WHEN 并行链路压测（真实 LiteFlow WHEN × setValue 组件）")
@Slf4j
@SpringBootTest(
    classes = DatabusSmokeTestApplication.class,
    properties = {
        "liteflow.enable=true",
        "liteflow.rule-source=classpath:liteflow/*.el.xml",
        // rule-db-sql 已进 classpath（生产走 Rule-DB），测试继续用本地规则文件需显式关掉 rule-db（官方逃生开关，二者互斥）
        "liteflow.rule-db.enabled=false"
    }
)
class DatabusWhenParallelStressTest {

    /** 并行分支数：when-parallel 扩 20 路。 */
    private static final int BRANCHES = 20;

    /** 连续执行次数。 */
    private static final int ROUNDS = 500;

    @Autowired
    private DatabusExecutor databusExecutor;

    @Autowired
    private ExpressGenerator expressGenerator;

    /** 20 路 WHEN 画布树（所有轮次复用同一份定义，与生产「一条链路反复执行」一致）。 */
    private CmpProperty whenTree;

    /** 由画布树生成的 EL 原文（executeByEl 绕过 normalize，直接原文建链）。 */
    private String elStr;

    @BeforeEach
    void setUp() {
        whenTree = buildWhenParallelTree(BRANCHES);
        assertTrue(expressGenerator.verifyELExpression(whenTree), "20 路 WHEN 画布树 EL 校验应通过");
        ELInfo elInfo = expressGenerator.generateEL(whenTree);
        assertTrue(elInfo != null && elInfo.getElStr() != null, "EL 不应为空");
        elStr = elInfo.getElStr();
        assertTrue(elStr.startsWith("WHEN("), () -> "EL 应以 WHEN( 开头，实际: " + elStr);
        log.info("[测试] 压测 EL: {}", elStr);
    }

    @Test
    @DisplayName("对照：20 路 WHEN 单次执行成功，20 个步骤与 20 个数据空间 key 齐全")
    void singleRun_allBranchesShouldSucceed() {
        DatabusExecutionResult result = databusExecutor.executeByEl(elStr, null, whenTree);
        assertTrue(result.isSuccess(), () -> "单次执行应成功，错误: " + result.getMessage());
        assertEquals(BRANCHES, result.getSteps().size(), "步骤数应等于分支数");
        assertAllBranchesPresent(result, 1);
    }

    @Test
    @DisplayName("压测：20 路 WHEN 连续执行 500 次，零失败零丢键零 CME")
    void whenParallel_20Branches_500Rounds_shouldNeverLoseData() {
        List<String> failures = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (int round = 1; round <= ROUNDS; round++) {
            DatabusExecutionResult result;
            try {
                result = databusExecutor.executeByEl(elStr, null, whenTree);
            } catch (Throwable t) {
                // executeByEl 正常情况下异常也包在结果里，这里兜底捕获线程池/建链层面的逃逸异常（如 CME）
                failures.add("#" + round + " 抛出未包装异常: " + t);
                if (failures.size() >= 10) {
                    break;
                }
                continue;
            }

            if (!result.isSuccess()) {
                failures.add("#" + round + " 执行失败: " + result.getMessage());
            } else if (result.getSteps().size() != BRANCHES) {
                failures.add("#" + round + " 步骤数=" + result.getSteps().size() + "，期望 " + BRANCHES);
            } else {
                List<Integer> missing = findMissingBranches(result);
                if (!missing.isEmpty()) {
                    failures.add("#" + round + " 丢键分支 " + missing);
                }
            }

            if (round % 50 == 0) {
                log.info("[测试] 压测进度 {}/{}，累计失败 {}", round, ROUNDS, failures.size());
            }
            if (failures.size() >= 10) {
                log.warn("[测试] 已累计 10 个失败，提前终止");
                break;
            }
        }

        long seconds = (System.currentTimeMillis() - start) / 1000;
        assertTrue(failures.isEmpty(),
            () -> "压测失败 " + failures.size() + " 个：" + String.join("; ", failures));
        log.info("[测试] ✅ 20 路 WHEN × {} 轮全部通过，耗时 {}s", ROUNDS, seconds);
    }

    /**
     * 构造与种子链 when-parallel 同构的 N 路 WHEN 画布树，
     * 每个分支写自己数据空间下的 out 字段（根级新路径，命中 createPath 竞态）。
     */
    private CmpProperty buildWhenParallelTree(int branches) {
        List<CmpProperty> children = new ArrayList<>(branches);
        for (int k = 1; k <= branches; k++) {
            String tag = "setValue" + k;
            // SetValueCfg：path 为写目标裸路径（起名字），value 为不含 {{}} 的字面量
            String data = "{\"path\":\"$." + tag + ".out\",\"value\":\"并行 " + k + "\"}";
            children.add(CmpProperty.builder()
                .id("setValue")
                .type("NodeComponent")
                .properties(Properties.builder().tag(tag).data(data).build())
                .build());
        }
        return CmpProperty.builder().type("WHEN").children(children).build();
    }

    /** 断言一次执行的最终上下文里 20 个分支的 out 值齐全且正确。 */
    private void assertAllBranchesPresent(DatabusExecutionResult result, int round) {
        List<Integer> missing = findMissingBranches(result);
        assertTrue(missing.isEmpty(),
            () -> "第" + round + "次执行缺失分支 " + missing + "，上下文: " + result.getContextJson());
    }

    /**
     * 解析上下文快照，返回 out 缺失或值不正确的分支编号列表（空列表表示全部正确）。
     */
    @SuppressWarnings("unchecked")
    private List<Integer> findMissingBranches(DatabusExecutionResult result) {
        Map<String, Object> root = (Map<String, Object>) JsonCodec.parse(result.getContextJson());
        List<Integer> missing = new ArrayList<>();
        for (int k = 1; k <= BRANCHES; k++) {
            String expected = "并行 " + k;
            Object node = root == null ? null : root.get("setValue" + k);
            Object out = node instanceof Map<?, ?> map ? map.get("out") : null;
            if (!expected.equals(out)) {
                missing.add(k);
            }
        }
        return missing;
    }
}
