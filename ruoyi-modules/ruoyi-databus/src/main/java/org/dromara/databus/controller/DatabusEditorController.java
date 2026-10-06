package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.context.JsonCodec;
import org.dromara.databus.context.InputParamValidator;
import org.dromara.databus.domain.bo.CmpPickBo;
import org.dromara.databus.domain.bo.PreviewRunBo;
import org.dromara.databus.domain.vo.CmpRecommendVo;
import org.dromara.databus.domain.vo.PreviewRunVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.executor.DatabusExecutionResult;
import org.dromara.databus.executor.DatabusExecutor;
import org.dromara.databus.service.IDatabusRecommendService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

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

    private final IDatabusRecommendService recommendService;

    /**
     * 组件插入推荐：弹层打开时拉取，返回按融合分降序的组件类型列表。
     * 仅返回有种子/真账信号的类型，未列出的由前端本地规则兜底；接口失败前端静默退回本地排序。
     * 登录即可用，不挂菜单权限点（编辑器内部交互，与 databus:editor:* 不耦合）。
     *
     * @param mode          插入场景（prepend/append/replace/insertEdge）
     * @param anchorType    前置组件 def.type，无锚点可不传
     * @param excludedTypes 需排除类型，逗号分隔（已存在 singleton、replace 自身）
     */
    @GetMapping("/recommend")
    public R<List<CmpRecommendVo>> recommend(@RequestParam String mode,
                                             @RequestParam(required = false) String anchorType,
                                             @RequestParam(required = false) String excludedTypes) {
        List<String> excluded = StringUtils.isBlank(excludedTypes)
            ? List.of()
            : Arrays.stream(excludedTypes.split(",")).map(String::trim).filter(StringUtils::isNotBlank).toList();
        return R.ok(recommendService.recommend(mode, anchorType, excluded));
    }

    /**
     * 上报一次真实选择（真账 +1）。只服务推荐质量，失败不影响画布操作，前端按 fire-and-forget 调用。
     */
    @PostMapping("/recommend/pick")
    public R<Void> recordPick(@Validated @RequestBody CmpPickBo bo) {
        recommendService.recordPick(bo);
        return R.ok();
    }

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
            // 脚本节点（script/booleanScript）没有 @LiteflowComponent，EL 引用的 nodeId 必须先在
            // FlowBus 中注册才能通过 LiteFlowChainELBuilder.validate / setEL 编译；故在 generateEL
            // 之前先把画布上的脚本节点预注册（其他业务组件已由 Spring 扫描自动注册）。
            databusExecutor.registerScriptNodes(jsonEl);

            ELInfo elInfo = expressGenerator.generateEL(jsonEl);
            elStr = elInfo == null ? null : elInfo.getElStr();
            boolean valid = expressGenerator.verifyELExpression(jsonEl);
            if (!valid) {
                return R.ok(PreviewRunVo.fail(elStr, "EL 表达式校验失败，请检查连线与节点配置"));
            }

            Object requestData = JsonCodec.parse(bo.getRequestJson());
            // 必填校验：默认值不注入试运行请求（前端已按默认值预填，这里只校验最终入参）
            InputParamValidator.validateRequired(bo.getInputParams(), requestData);
            DatabusExecutionResult executionResult = databusExecutor.executeByEl(elStr, requestData, jsonEl);
            return R.ok(PreviewRunVo.executed(elStr, executionResult));
        } catch (Exception e) {
            log.warn("preview-run 生成/执行失败: {}", e.getMessage());
            return R.ok(PreviewRunVo.fail(elStr, "试运行失败：" + e.getMessage()));
        }
    }

}
