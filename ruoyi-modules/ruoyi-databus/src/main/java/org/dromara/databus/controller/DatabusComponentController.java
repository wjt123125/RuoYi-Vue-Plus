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
import org.dromara.databus.domain.vo.DatabusComponentVo;
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
     * 查询全部启用组件（编辑器组件面板物料，不分页）
     */
    @SaCheckPermission("databus:editor:list")
    @GetMapping("/options")
    public R<List<DatabusComponentVo>> options() {
        return R.ok(componentService.queryEnabledList());
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

}
