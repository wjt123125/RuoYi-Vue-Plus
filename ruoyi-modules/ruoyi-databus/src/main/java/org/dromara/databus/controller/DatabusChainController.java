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
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

/**
 * 数据总线链路定义接口（编辑器 load/save 配套）。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/chain")
public class DatabusChainController extends BaseController {

    private final IDatabusChainService chainService;

    /**
     * 分页查询链路列表
     */
    @SaCheckPermission("databus:editor:list")
    @GetMapping("/list")
    public R<PageResult<DatabusChainVo>> list(DatabusChainBo bo, PageQuery pageQuery) {
        return R.ok(chainService.queryPageList(bo, pageQuery));
    }

    /**
     * 加载链路详情（编辑器还原画布用）
     *
     * @param id 链路主键
     */
    @SaCheckPermission("databus:editor:query")
    @GetMapping("/{id}")
    public R<DatabusChainVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable("id") Long id) {
        return R.ok(chainService.queryById(id));
    }

    /**
     * 保存链路草稿（新增）
     */
    @SaCheckPermission("databus:editor:add")
    @Log(title = "数据总线链路", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated @RequestBody DatabusChainBo bo) {
        return toAjax(chainService.insertByBo(bo));
    }

    /**
     * 保存链路草稿（修改）
     */
    @SaCheckPermission("databus:editor:edit")
    @Log(title = "数据总线链路", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated @RequestBody DatabusChainBo bo) {
        return toAjax(chainService.updateByBo(bo));
    }

    /**
     * 删除链路
     *
     * @param ids 链路主键串
     */
    @SaCheckPermission("databus:editor:remove")
    @Log(title = "数据总线链路", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(chainService.deleteByIds(Arrays.asList(ids)));
    }

}
