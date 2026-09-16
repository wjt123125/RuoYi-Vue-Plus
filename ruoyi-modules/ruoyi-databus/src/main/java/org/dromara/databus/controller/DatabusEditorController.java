package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.vo.PreviewRunVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
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

    /**
     * 试运行（阶段 1A）：基于当前画布内容生成 EL 并做语法校验，不落库、不真执行。
     * 真正执行链路待阶段 1B DatabusExecutor 落地后扩展。
     *
     * @param jsonEl 画布组件树
     * @return EL 表达式与校验结果
     */
    @SaCheckPermission("databus:editor:run")
    @PostMapping("/preview-run")
    public R<PreviewRunVo> previewRun(@RequestBody CmpProperty jsonEl) {
        String elStr = null;
        try {
            ELInfo elInfo = expressGenerator.generateEL(jsonEl);
            elStr = elInfo == null ? null : elInfo.getElStr();
            boolean valid = expressGenerator.verifyELExpression(jsonEl);
            if (valid) {
                return R.ok(PreviewRunVo.ok(elStr));
            }
            return R.ok(PreviewRunVo.fail(elStr, "EL 表达式校验失败，请检查连线与节点配置"));
        } catch (Exception e) {
            log.warn("preview-run 生成/校验 EL 失败: {}", e.getMessage());
            return R.ok(PreviewRunVo.fail(elStr, "EL 生成失败：" + e.getMessage()));
        }
    }

}
