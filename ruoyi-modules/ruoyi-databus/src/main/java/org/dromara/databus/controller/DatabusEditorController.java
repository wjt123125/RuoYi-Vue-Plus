package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.CmpPickBo;
import org.dromara.databus.domain.bo.PreviewRunBo;
import org.dromara.databus.domain.vo.CmpRecommendVo;
import org.dromara.databus.domain.vo.PreviewRunVo;
import org.dromara.databus.service.IDatabusPreviewRunService;
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
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/editor")
public class DatabusEditorController extends BaseController {

    private final IDatabusPreviewRunService previewRunService;

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
     * 六步逻辑已抽到 {@link IDatabusPreviewRunService}，AI 工具与人工界面共用。
     *
     * @param bo 画布组件树 + 执行入参 JSON
     * @return EL 表达式、校验结果与执行结果
     */
    @SaCheckPermission("databus:editor:run")
    @PostMapping("/preview-run")
    public R<PreviewRunVo> previewRun(@RequestBody PreviewRunBo bo) {
        return R.ok(previewRunService.run(bo));
    }

}