package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.bo.ScriptRollbackBo;
import org.dromara.databus.domain.bo.ScriptSaveBo;
import org.dromara.databus.domain.vo.ComponentDomainVo;
import org.dromara.databus.domain.vo.ComponentGroupVo;
import org.dromara.databus.domain.vo.ComponentOptionsVo;
import org.dromara.databus.domain.vo.DatabusComponentVersionVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.domain.vo.ScriptRuntimeVo;
import org.dromara.databus.domain.vo.ScriptSaveResultVo;
import org.dromara.databus.script.host.ScriptCompileException;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * 数据总线组件元信息接口。
 * <p>
 * 编辑器组件面板通过 {@code /options} 拉取启用组件物料；
 * 组件管理（增删改查）为阶段 3 管理页预留。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/component")
public class DatabusComponentController extends BaseController {

    private final IDatabusComponentService componentService;

    /**
     * 分页查询组件元信息列表（组件管理页用）
     */
    @SaCheckPermission("databus:component:list")
    @GetMapping("/list")
    public R<PageResult<DatabusComponentVo>> list(DatabusComponentBo bo, PageQuery pageQuery) {
        return R.ok(componentService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询编辑器物料合流选项（内置注解件 + 启用的自定义件，不分页）
     */
    @SaCheckPermission("databus:editor:list")
    @GetMapping("/options")
    public R<ComponentOptionsVo> options() {
        return R.ok(componentService.queryOptions());
    }

    /**
     * 查询物料面板分组字典（编辑器面板与台账树共用，不分页）
     */
    @SaCheckPermission("databus:editor:list")
    @GetMapping("/groups")
    public R<List<ComponentGroupVo>> groups() {
        return R.ok(componentService.queryGroups());
    }

    /**
     * 查询物料业务域字典（含兜底域标记 isDefault，不分页）
     */
    @SaCheckPermission("databus:editor:list")
    @GetMapping("/domains")
    public R<List<ComponentDomainVo>> domains() {
        return R.ok(componentService.queryDomains());
    }

    /**
     * 获取组件元信息详细信息
     *
     * @param id 组件主键
     */
    @SaCheckPermission("databus:component:query")
    @GetMapping("/{id}")
    public R<DatabusComponentVo> getInfo(@NotNull(message = "主键不能为空")
                                         @PathVariable("id") Long id) {
        return R.ok(componentService.queryById(id));
    }

    /**
     * 新增组件元信息
     */
    @SaCheckPermission("databus:component:add")
    @Log(title = "数据总线组件", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated @RequestBody DatabusComponentBo bo) {
        return toAjax(componentService.insertByBo(bo));
    }

    /**
     * 修改组件元信息
     */
    @SaCheckPermission("databus:component:edit")
    @Log(title = "数据总线组件", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated @RequestBody DatabusComponentBo bo) {
        return toAjax(componentService.updateByBo(bo));
    }

    /**
     * 删除组件元信息
     *
     * @param ids 组件主键串
     */
    @SaCheckPermission("databus:component:remove")
    @Log(title = "数据总线组件", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(componentService.deleteByIds(Arrays.asList(ids)));
    }

    /**
     * 保存组件脚本正文（保存即编译，失败不落库；成功热更全局生效，版本号 +1）。
     * <p>独立权限点 {@code databus:component:script:edit}（受信作者模型，等同服务端代码发布）。
     * 编译失败仍返回 R.ok，success=false 且 data 为行列诊断明细（前端拦截器会吞业务失败码的 data）。
     */
    @SaCheckPermission("databus:component:script:edit")
    @Log(title = "数据总线组件脚本", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/script")
    public R<ScriptSaveResultVo> saveScript(@Validated @RequestBody ScriptSaveBo bo) {
        try {
            return R.ok(componentService.saveScript(bo));
        } catch (ScriptCompileException e) {
            return R.ok(ScriptSaveResultVo.fail(e.getMessage(), e.getDiagnostics()));
        }
    }

    /**
     * 一键回滚脚本到指定历史版本（旧源码重走保存管线，产生新版本行）。
     */
    @SaCheckPermission("databus:component:script:edit")
    @Log(title = "数据总线组件脚本回滚", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/script/rollback")
    public R<ScriptSaveResultVo> rollbackScript(@Validated @RequestBody ScriptRollbackBo bo) {
        try {
            return R.ok(componentService.rollbackScript(bo));
        } catch (ScriptCompileException e) {
            return R.ok(ScriptSaveResultVo.fail(e.getMessage(), e.getDiagnostics()));
        }
    }

    /**
     * 查询组件脚本版本历史（版本号倒序，含正文；脚本源码仅脚本编辑权限可见）。
     */
    @SaCheckPermission("databus:component:script:edit")
    @GetMapping("/script/{id}/versions")
    public R<List<DatabusComponentVersionVo>> versions(@NotNull(message = "主键不能为空")
                                                        @PathVariable("id") Long id) {
        return R.ok(componentService.queryVersions(id));
    }

    /**
     * 查询库存脚本件运行时注册健康（启动期失败件露出，台账页用）。
     */
    @SaCheckPermission("databus:component:list")
    @GetMapping("/script/runtime")
    public R<List<ScriptRuntimeVo>> scriptRuntime() {
        return R.ok(componentService.queryRuntimeHealth());
    }

}
