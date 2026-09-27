package org.dromara.databus.context;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DatabusContext} 并发安全回归测试（2026-09-27 ReentrantReadWriteLock 修复配套）。
 * <p>
 * 缺陷背景见 docs/wiki/databus-context-concurrency.md：jayway 底层是普通
 * {@code LinkedHashMap}，WHEN 并行下多个分支在<b>同一个上下文根 Map</b>上并发
 * {@code createPath}（pathExists→put 的 check-then-act 复合操作），修复前高概率
 * 丢键 / {@link java.util.ConcurrentModificationException}；分支完成时步骤采集
 * 并发序列化 JSON 也可能撞上其他分支的写入。
 * <p>
 * 本类为<b>不依赖 Spring</b> 的纯并发锤测：用 {@link CyclicBarrier} 起跑门让所有
 * 写线程在同一瞬间打向同一个根 Map（空 Map 首批 put 正落在扩容窗口），重复数百轮
 * 放大竞态。修复前应高概率失败，修复后必须零异常、零丢键。
 *
 * @author databus
 */
@Tag("dev")
@DisplayName("DatabusContext WHEN 并发安全（读写锁回归）")
@Slf4j
class DatabusContextConcurrencyTest {

    /** 并行分支数：与压测验收口径一致（when-parallel 扩 20 路）。 */
    private static final int BRANCHES = 20;

    /** 写竞争轮次：每轮一个全新空上下文，重复 500 轮。 */
    private static final int WRITE_ROUNDS = 500;

    /** 读写混合轮次。 */
    private static final int MIXED_ROUNDS = 200;

    /** 并发读线程数：步骤采集场景的近似（每分支完成时各拍一次快照）。 */
    private static final int READERS = 8;

    @Test
    @DisplayName("20 线程同一瞬间在根上新建 20 个 key × 500 轮：零丢键零 CME")
    void concurrentRootWrites_shouldNotLoseKeys() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BRANCHES);
        // 起跑门：20 个写线程全部就位后同时放行，最大化对根 Map 的竞争
        CyclicBarrier startGate = new CyclicBarrier(BRANCHES);
        Queue<String> errors = new ConcurrentLinkedQueue<>();
        try {
            for (int round = 1; round <= WRITE_ROUNDS; round++) {
                final int roundNo = round;
                DatabusContext context = DatabusContext.empty();
                List<Future<?>> futures = new ArrayList<>(BRANCHES);
                for (int branch = 1; branch <= BRANCHES; branch++) {
                    final int branchNo = branch;
                    futures.add(pool.submit(() -> {
                        try {
                            startGate.await(10, TimeUnit.SECONDS);
                            // 与 setValue 组件同构：$.setValueN.out 是根级新路径，
                            // write 内部 createPath 直接 document.put("$", tag, ...)
                            context.write("$.setValue" + branchNo + ".out", "并行 " + branchNo);
                        } catch (Throwable t) {
                            errors.add("第" + roundNo + "轮 分支" + branchNo + " 写入异常: " + t);
                        }
                    }));
                }
                awaitAll(futures);

                for (int branch = 1; branch <= BRANCHES; branch++) {
                    final int b = branch;
                    assertEquals("并行 " + b,
                        context.read("$.setValue" + b + ".out"),
                        () -> "第" + roundNo + "轮 setValue" + b
                            + " 丢键或值不正确（累计异常: " + errors.size() + "）");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(errors.isEmpty(),
            () -> "并发写入累计异常 " + errors.size() + " 个，首条: " + errors.peek());
        log.info("[测试] 根写入竞态 {} 轮 × {} 路全部通过", WRITE_ROUNDS, BRANCHES);
    }

    @Test
    @DisplayName("20 路写入期间 8 个读线程持续 toJsonString/快照/读取：读侧零 CME 且最终数据完整")
    void concurrentReadsDuringWrites_shouldNeverSeeConcurrentModification() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BRANCHES + READERS);
        Queue<String> errors = new ConcurrentLinkedQueue<>();
        try {
            for (int round = 1; round <= MIXED_ROUNDS; round++) {
                DatabusContext context = DatabusContext.empty();
                AtomicBoolean stop = new AtomicBoolean(false);
                CountDownLatch readersReady = new CountDownLatch(READERS);
                CyclicBarrier writerGate = new CyclicBarrier(BRANCHES);
                List<Future<?>> readerFutures = new ArrayList<>(READERS);

                final int roundNo = round;
                // 读线程先转起来：模拟各分支完成时步骤采集钩子并发拍快照/序列化
                for (int r = 0; r < READERS; r++) {
                    readerFutures.add(pool.submit(() -> {
                        readersReady.countDown();
                        long spin = 0;
                        while (!stop.get()) {
                            try {
                                int branch = (int) (spin % BRANCHES) + 1;
                                String tag = "setValue" + branch;
                                // 三个读入口各走一遍：全量序列化 / 子树快照 / 单路径读取
                                context.toJsonString();
                                context.snapshotDataSpace(tag);
                                context.readOptional("$." + tag + ".out");
                                spin++;
                            } catch (Throwable t) {
                                errors.add("第" + roundNo + "轮 读线程异常: " + t);
                                return;
                            }
                        }
                    }));
                }

                readersReady.await(5, TimeUnit.SECONDS);
                List<Future<?>> writerFutures = new ArrayList<>(BRANCHES);
                for (int branch = 1; branch <= BRANCHES; branch++) {
                    final int branchNo = branch;
                    writerFutures.add(pool.submit(() -> {
                        try {
                            writerGate.await(10, TimeUnit.SECONDS);
                            // 比根写入多一层：拉长写临界区，增加与读线程的交错概率
                            context.write("$.setValue" + branchNo + ".trace.note", "trace " + branchNo);
                            context.write("$.setValue" + branchNo + ".out", "并行 " + branchNo);
                        } catch (Throwable t) {
                            errors.add("第" + roundNo + "轮 分支" + branchNo + " 写入异常: " + t);
                        }
                    }));
                }

                awaitAll(writerFutures);
                stop.set(true);
                awaitAll(readerFutures);

                for (int branch = 1; branch <= BRANCHES; branch++) {
                    final int b = branch;
                    assertEquals("并行 " + b,
                        context.read("$.setValue" + b + ".out"),
                        () -> "第" + roundNo + "轮 setValue" + b + " 最终值不正确");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(errors.isEmpty(),
            () -> "读写混合累计异常 " + errors.size() + " 个，首条: " + errors.peek());
        log.info("[测试] 读写混合 {} 轮（{} 写 + {} 读并发）全部通过",
            MIXED_ROUNDS, BRANCHES, READERS);
    }

    @Test
    @DisplayName("20 线程并发写各自多层新路径 $.branchN.a.b.c × 300 轮：深路径零丢失")
    void concurrentDeepNestedWrites_shouldKeepEveryBranch() throws Exception {
        int rounds = 300;
        ExecutorService pool = Executors.newFixedThreadPool(BRANCHES);
        CyclicBarrier startGate = new CyclicBarrier(BRANCHES);
        Queue<String> errors = new ConcurrentLinkedQueue<>();
        try {
            for (int round = 1; round <= rounds; round++) {
                final int roundNo = round;
                DatabusContext context = DatabusContext.empty();
                List<Future<?>> futures = new ArrayList<>(BRANCHES);
                for (int branch = 1; branch <= BRANCHES; branch++) {
                    final int branchNo = branch;
                    futures.add(pool.submit(() -> {
                        try {
                            startGate.await(10, TimeUnit.SECONDS);
                            // 多层自动建路径：createPath 内连续 pathExists→put
                            context.write("$.branch" + branchNo + ".a.b.c", branchNo);
                        } catch (Throwable t) {
                            errors.add("第" + roundNo + "轮 分支" + branchNo + " 深路径写入异常: " + t);
                        }
                    }));
                }
                awaitAll(futures);

                for (int branch = 1; branch <= BRANCHES; branch++) {
                    final int b = branch;
                    assertEquals(Integer.valueOf(b), context.read("$.branch" + b + ".a.b.c"),
                        () -> "第" + roundNo + "轮 branch" + b + " 深路径丢键");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(errors.isEmpty(),
            () -> "深路径并发写入累计异常 " + errors.size() + " 个，首条: " + errors.peek());
        log.info("[测试] 多层路径竞态 {} 轮 × {} 路全部通过", rounds, BRANCHES);
    }

    /** 等一批任务全部结束；单个任务卡住超过 15 秒即让用例失败而不是无限挂起。 */
    private void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
    }
}
