package org.dromara.databus.script.host;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「补课老师」：保存时干的事，启动时原样再干一遍。
 *
 * <p>保存时编译出来的类只活在内存里，重启就全没了；好在源码存在数据库里，
 * 什么都不丢。所以应用起来后，这个 Runner 把库里启用中的脚本件全捞出来，
 * 逐个「编译 + 注册」，把工位一个个重新坐上——库里有什么，现网就有什么。
 *
 * <p>排位 @Order(200)，刻意排在内置组件扫描（ComponentSchemaScanner，
 * @Order(100)）之后：FlowBus 先收内置 @LiteflowComponent，库存脚本件随后
 * 按同 code 裸 put 接管（比如脚本版的 httpRequest 盖掉内置版）。
 *
 * <p>容错是底线：一个坏脚本编不过，只记一笔失败台账（markFailed）加一条
 * ERROR 日志，绝不拖死整个应用——别的件照常上工，坏件在运行时健康接口里
 * 露脸，链路执行到它才报「节点缺失」，修好保存一次即上线。
 *
 * <p>停用件（status=1）不捞不注册；停用动作本身也不摘 FlowBus 节点
 * （已编译链路手里还捏着节点副本），重启后停用件自然不进 FlowBus——
 * 紧急止血以重启为界。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(200)
public class ScriptComponentStartupRegistrar implements ApplicationRunner {

    /** 组件台账的状态位：0 启用（只有启用件才来补课）。 */
    private static final String STATUS_ENABLED = "0";

    private final DatabusComponentMapper componentMapper;

    private final JavaSourceCompiler scriptCompiler;

    private final ScriptComponentRegistrar scriptRegistrar;

    /**
     * 启动补课主流程：捞库存（启用 + 有源码，按 id 升序保证日志顺序稳定）
     * → 逐件「编译 + 注册」→ 末了数出成败各几个。单件失败走 markFailed
     * 记台账、循环继续，不停课。
     */
    @Override
    public void run(ApplicationArguments args) {
        List<DatabusComponent> rows = componentMapper.selectList(Wrappers.<DatabusComponent>lambdaQuery()
            .eq(DatabusComponent::getStatus, STATUS_ENABLED)
            .isNotNull(DatabusComponent::getScriptBody)
            .ne(DatabusComponent::getScriptBody, "")
            .orderByAsc(DatabusComponent::getId));
        if (rows.isEmpty()) {
            log.info("[databus-script] 无库存脚本件，跳过启动注册");
            return;
        }
        int ok = 0;
        int failed = 0;
        for (DatabusComponent row : rows) {
            try {
                ScriptArtifact artifact = scriptCompiler.compile(row.getScriptBody());
                scriptRegistrar.register(row, artifact);
                ok++;
            } catch (Exception e) {
                failed++;
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                scriptRegistrar.markFailed(row.getId(), row.getComponentCode(), row.getVersion(), message);
                log.error("[databus-script] 库存脚本件启动注册失败 code={}, version={}：{}",
                    row.getComponentCode(), row.getVersion(), message, e);
            }
        }
        log.info("[databus-script] 库存脚本件启动注册完成：成功 {} 个，失败 {} 个（共 {} 个）",
            ok, failed, rows.size());
    }
}
