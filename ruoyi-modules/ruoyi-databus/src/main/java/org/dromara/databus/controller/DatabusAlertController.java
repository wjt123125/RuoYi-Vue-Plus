package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.DatabusAlertRecordBo;
import org.dromara.databus.domain.bo.DatabusAlertRuleBo;
import org.dromara.databus.domain.vo.AlertEvaluateVo;
import org.dromara.databus.domain.vo.DatabusAlertRecordVo;
import org.dromara.databus.domain.vo.DatabusAlertRuleVo;
import org.dromara.databus.service.IDatabusAlertService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * 数据总线告警管理接口（阶段5 监控体系 · 告警引擎 v1）。
 *
 * <p>端点：
 * <ul>
 *   <li>GET    /databus/alert/rule/list       告警规则分页</li>
 *   <li>GET    /databus/alert/rule/{id}       告警规则详情</li>
 *   <li>POST   /databus/alert/rule            新增规则</li>
 *   <li>PUT    /databus/alert/rule            修改规则</li>
 *   <li>DELETE /databus/alert/rule/{ids}      批量删除规则（记录保留，便于追溯）</li>
 *   <li>GET    /databus/alert/record/list     告警记录分页（历史告警）</li>
 *   <li>POST   /databus/alert/evaluate        手动触发规则评估（定时任务每分钟自动执行）</li>
 * </ul>
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/alert")
public class DatabusAlertController extends BaseController {

    private final IDatabusAlertService alertService;

    /**
     * 分页查询告警规则。
     */
    @SaCheckPermission("databus:alert:query")
    @GetMapping("/rule/list")
    public R<PageResult<DatabusAlertRuleVo>> ruleList(DatabusAlertRuleBo bo, PageQuery pageQuery) {
        return R.ok(alertService.queryRulePageList(bo, pageQuery));
    }

    /**
     * 告警规则详情。
     */
    @SaCheckPermission("databus:alert:query")
    @GetMapping("/rule/{id}")
    public R<DatabusAlertRuleVo> ruleInfo(@NotNull(message = "主键不能为空")
                                           @PathVariable("id") Long id) {
        return R.ok(alertService.queryRuleById(id));
    }

    /**
     * 新增告警规则。
     */
    @SaCheckPermission("databus:alert:add")
    @Log(title = "数据总线告警规则", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/rule")
    public R<Void> ruleAdd(@Validated(AddGroup.class) @RequestBody DatabusAlertRuleBo bo) {
        return toAjax(alertService.insertRule(bo));
    }

    /**
     * 修改告警规则。
     */
    @SaCheckPermission("databus:alert:edit")
    @Log(title = "数据总线告警规则", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/rule")
    public R<Void> ruleEdit(@Validated(EditGroup.class) @RequestBody DatabusAlertRuleBo bo) {
        return toAjax(alertService.updateRule(bo));
    }

    /**
     * 批量删除告警规则（不级联删记录）。
     */
    @SaCheckPermission("databus:alert:remove")
    @Log(title = "数据总线告警规则", businessType = BusinessType.DELETE)
    @DeleteMapping("/rule/{ids}")
    public R<Void> ruleRemove(@NotEmpty(message = "主键不能为空")
                              @PathVariable Long[] ids) {
        return toAjax(alertService.deleteRuleByIds(Arrays.asList(ids)));
    }

    /**
     * 分页查询告警记录（历史告警，只读）。
     */
    @SaCheckPermission("databus:alert:query")
    @GetMapping("/record/list")
    public R<PageResult<DatabusAlertRecordVo>> recordList(DatabusAlertRecordBo bo, PageQuery pageQuery) {
        return R.ok(alertService.queryRecordPageList(bo, pageQuery));
    }

    /**
     * 手动触发规则评估（定时任务每分钟自动执行，此端点供运维验证与补跑）。
     */
    @SaCheckPermission("databus:alert:evaluate")
    @Log(title = "数据总线告警评估", businessType = BusinessType.OTHER)
    @PostMapping("/evaluate")
    public R<AlertEvaluateVo> evaluate() {
        return R.ok(alertService.evaluate());
    }

}