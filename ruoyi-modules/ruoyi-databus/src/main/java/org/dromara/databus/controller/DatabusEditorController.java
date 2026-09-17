package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.context.JsonCodec;
import org.dromara.databus.domain.bo.PreviewRunBo;
import org.dromara.databus.domain.vo.PreviewRunVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.executor.DatabusExecutionResult;
import org.dromara.databus.executor.DatabusExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据总线编辑器配套接口。
 *
 * @author databus
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/editor")
public class DatabusEditorController extends BaseController {

    private final ExpressGenerator expressGenerator;

    private final DatabusExecutor databusExecutor;

    /**
     * 试运行（阶段 1C）：基于当前画布内容生成 EL、语法校验通过后直接按 EL 真执行（不落库），
     * 返回每步成败耗时与上下文快照。执行失败也以 HTTP 200 返回，错误信息在 errorMessage 中。
     *
     * @param bo 画布组件树 + 执行入参 JSON
     * @return EL 表达式、校验结果与执行结果
     */
    @SaCheckPermission("databus:editor:run")
    @PostMapping("/preview-run")
    public R<PreviewRunVo> previewRun(@RequestBody PreviewRunBo bo) {
        CmpProperty jsonEl = bo.getJsonEl();
        String elStr = null;
        try {
            ELInfo elInfo = expressGenerator.generateEL(jsonEl);
            elStr = elInfo == null ? null : elInfo.getElStr();
            boolean valid = expressGenerator.verifyELExpression(jsonEl);
            if (!valid) {
                return R.ok(PreviewRunVo.fail(elStr, "EL 表达式校验失败，请检查连线与节点配置"));
            }

            Object requestData = JsonCodec.parse(bo.getRequestJson());
            DatabusExecutionResult executionResult = databusExecutor.executeByEl(elStr, requestData);
            return R.ok(PreviewRunVo.executed(elStr, executionResult));
        } catch (Exception e) {
            log.warn("preview-run 生成/执行失败: {}", e.getMessage());
            return R.ok(PreviewRunVo.fail(elStr, "试运行失败：" + e.getMessage()));
        }
    }

}
