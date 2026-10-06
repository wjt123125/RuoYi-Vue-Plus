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
 * 库存脚本件启动全量注册（容错）。
 *
 * <p>运行时机晚于内置 Bean 扫描（{@code ComponentSchemaScanner} @Order(100)，本类 @Order(200)）：
 * FlowBus 先收内置 @LiteflowComponent，库存件随后按同 code 裸 put 接管（如 httpRequest）。
 * 单件编译/实例化/注册失败只记录健康状态与 ERROR 日志，不阻断其他组件与应用启动；
 * 失败件在组件台账露出（运行时健康接口），其链路执行期才暴露缺失错误。
 *
 * <p>停用行（status=1）不注册；停用不主动摘除 FlowBus 节点（已编译链路持有节点副本），
 * 重启后停用件自然不进 FlowBus——紧急止血以重启为界。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(200)
public class ScriptComponentStartupRegistrar implements ApplicationRunner {

    private static final String STATUS_ENABLED = "0";

    private final DatabusComponentMapper componentMapper;

    private final JavaSourceCompiler scriptCompiler;

    private final ScriptComponentRegistrar scriptRegistrar;

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
