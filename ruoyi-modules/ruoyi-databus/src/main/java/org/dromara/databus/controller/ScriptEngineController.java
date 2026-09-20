package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.yomahub.liteflow.script.ScriptExecutor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 脚本引擎探测接口。
 * <p>
 * 通过 Java SPI（{@link ServiceLoader}）列举 classpath 已安装的 LiteFlow 脚本执行器，
 * 供前端脚本物料 language 下拉动态生成。装哪个引擎 jar 出现哪个语言；
 * 与 connector 物料市场同一思路，未来语言包热生效。
 *
 * <p>规格档：{@code docs/wiki/databus-script-component.md} §4 语言引擎 SPI 热插拔。
 * 一期仅装 {@code liteflow-script-groovy}，故仅返回 "groovy"。
 *
 * @author databus
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/databus/script")
public class ScriptEngineController extends BaseController {

    /**
     * 列出当前已安装的脚本引擎语言清单。
     * <p>实现：{@code ServiceLoader.load(ScriptExecutor.class)} 遍历所有 SPI 注册的实现，
     * 对每个 executor 取 {@code scriptType().getDisplayName()}（如 "groovy"/"qlexpress"/"js"），
     * 去重后按 SPI 返回顺序返回。后端原样返回 displayName，前端兜底处理大小写。
     * <p>不依赖 LiteFlow 内部 {@code ScriptExecutorFactory.scriptExecutorMap}（私有字段，不反射）。
     */
    @SaCheckPermission("databus:editor:list")
    @GetMapping("/engines")
    public R<List<ScriptEngineVO>> engines() {
        Set<String> seen = new LinkedHashSet<>();
        List<ScriptEngineVO> list = new ArrayList<>();
        try {
            // ServiceLoader.load 返回的迭代器每次 next 都会 new 出实现类实例；
            // 不调 init() 也能读 scriptType（abstract 方法，返回静态枚举，不依赖实例状态）。
            // 这里仍按 LiteFlow 内部 ScriptExecutorFactory 的范式调 init() 触发生命周期钩子，
            // 避免某些 executor 在 init 后才完成 scriptType 的兜底赋值。
            for (ScriptExecutor executor : ServiceLoader.load(ScriptExecutor.class)) {
                try {
                    executor.init();
                    String language = executor.scriptType().getDisplayName();
                    if (language != null && !language.isBlank() && seen.add(language)) {
                        list.add(new ScriptEngineVO(language));
                    }
                } catch (Exception e) {
                    log.warn("[databus] 脚本引擎探测失败 type={}: {}", executor.getClass().getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[databus] 脚本引擎 SPI 加载异常: {}", e.getMessage());
        }
        return R.ok(list);
    }

    /**
     * 脚本引擎语言 VO，字段即语言 displayName（如 "groovy"）。
     */
    @Data
    public static class ScriptEngineVO {

        /** LiteFlow ScriptTypeEnum.displayName，如 "groovy" */
        private String language;

        public ScriptEngineVO() {
        }

        public ScriptEngineVO(String language) {
            this.language = language;
        }
    }
}
