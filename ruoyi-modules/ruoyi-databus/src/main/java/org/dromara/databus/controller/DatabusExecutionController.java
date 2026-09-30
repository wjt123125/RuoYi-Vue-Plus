package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.DatabusExecutionBo;
import org.dromara.databus.domain.bo.ManualExecuteBo;
import org.dromara.databus.domain.vo.DatabusExecutionVo;
import org.dromara.databus.domain.vo.ExecutionCleanupVo;
import org.dromara.databus.domain.vo.ExecutionDetailVo;
import org.dromara.databus.executor.DatabusExecutionResult;
import org.dromara.databus.service.IDatabusExecutionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据总线执行记录接口（设计档 §4.2/§4.5）。
 * <p>
 * 只提供只读查询（分页/详情）、执行入口（手动执行/重跑）与保留期清理，无业务修改——
 * 记录只增不改，过期数据由定时任务物理删除（POST /cleanup 可手动触发）。执行成败均以
 * HTTP 200 返回（结果在 {@link DatabusExecutionResult#isSuccess()}），与试运行 preview-run
 * 同口径；链路非已发布等前置校验失败走全局异常提示。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/execution")
public class DatabusExecutionController extends BaseController {

    private final IDatabusExecutionService executionService;

    /**
     * 分页查询执行记录
     */
    @SaCheckPermission("databus:execution:list")
    @GetMapping("/list")
    public R<PageResult<DatabusExecutionVo>> list(DatabusExecutionBo bo, PageQuery pageQuery) {
        return R.ok(executionService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询执行记录详情（总账 + FULL 档节点明细行）
     *
     * @param id 执行记录主键
     */
    @SaCheckPermission("databus:execution:query")
    @GetMapping("/{id}")
    public R<ExecutionDetailVo> getInfo(@NotNull(message = "主键不能为空")
                                        @PathVariable("id") Long id) {
        return R.ok(executionService.queryDetail(id));
    }

    /**
     * 手动执行已发布链路（走正式 execute 通道，按 log_level 落新记录）
     */
    @SaCheckPermission("databus:execution:execute")
    @Log(title = "数据总线手动执行", businessType = BusinessType.OTHER)
    @PostMapping("/execute")
    public R<DatabusExecutionResult> execute(@Validated @RequestBody ManualExecuteBo bo) {
        return R.ok(executionService.manualExecute(bo));
    }

    /**
     * 重跑：以历史记录入参再走一次正式 execute，产生一条新记录（原记录不变）
     *
     * @param id 历史执行记录主键
     */
    @SaCheckPermission("databus:execution:execute")
    @Log(title = "数据总线执行重跑", businessType = BusinessType.OTHER)
    @PostMapping("/rerun/{id}")
    public R<DatabusExecutionResult> rerun(@NotNull(message = "主键不能为空")
                                           @PathVariable("id") Long id) {
        return R.ok(executionService.rerun(id));
    }

    /**
     * 手动触发保留期清理（定时任务每天凌晨按配置自动执行，此端点供运维即时清理与验证）。
     *
     * @param retentionDays 保留天数覆盖，不传则取 databus.execution.cleanup.retention-days（默认 30）
     */
    @SaCheckPermission("databus:execution:remove")
    @Log(title = "数据总线执行记录清理", businessType = BusinessType.DELETE)
    @PostMapping("/cleanup")
    public R<ExecutionCleanupVo> cleanup(@RequestParam(required = false) Integer retentionDays) {
        return R.ok(executionService.cleanup(retentionDays));
    }

}
