package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.ChainMoveBo;
import org.dromara.databus.domain.bo.DatabusChainDirectoryBo;
import org.dromara.databus.domain.vo.DatabusChainDirectoryVo;
import org.dromara.databus.service.IDatabusChainDirectoryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * 链路目录接口（链路工作台资源树配套：目录 CRUD + 链路归属移动）。
 * <p>
 * 目录树为全量平表（无分页），前端组树；链路拖拽移动走 move-chain 端点改写
 * databus_chain.directory_id（空=未归组）。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/chain/directory")
public class DatabusChainDirectoryController extends BaseController {

    private final IDatabusChainDirectoryService directoryService;

    /**
     * 查询全部目录（平表，前端组树）
     */
    @SaCheckPermission("databus:chain:directory:list")
    @GetMapping("/list")
    public R<List<DatabusChainDirectoryVo>> list() {
        return R.ok(directoryService.queryList());
    }

    /**
     * 加载目录详情
     *
     * @param id 目录主键
     */
    @SaCheckPermission("databus:chain:directory:query")
    @GetMapping("/{id}")
    public R<DatabusChainDirectoryVo> getInfo(@NotNull(message = "主键不能为空")
                                              @PathVariable("id") Long id) {
        return R.ok(directoryService.queryById(id));
    }

    /**
     * 新增目录（工作台树右键新建）
     */
    @SaCheckPermission("databus:chain:directory:add")
    @Log(title = "链路目录", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated @RequestBody DatabusChainDirectoryBo bo) {
        return toAjax(directoryService.insertByBo(bo));
    }

    /**
     * 修改目录（重命名/排序/拖拽换父；换父后端做自指与环校验）
     */
    @SaCheckPermission("databus:chain:directory:edit")
    @Log(title = "链路目录", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated @RequestBody DatabusChainDirectoryBo bo) {
        return toAjax(directoryService.updateByBo(bo));
    }

    /**
     * 删除目录（子目录非空/挂链非空均拦截，不做级联删除）
     *
     * @param ids 目录主键串
     */
    @SaCheckPermission("databus:chain:directory:remove")
    @Log(title = "链路目录", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(directoryService.deleteByIds(Arrays.asList(ids)));
    }

    /**
     * 移动链路目录归属（工作台树拖拽链路：directoryId 空=移出到未归组）
     *
     * @param bo 链路主键 + 目标目录主键（可空）
     */
    @SaCheckPermission("databus:chain:directory:edit")
    @Log(title = "链路目录归属", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/move-chain")
    public R<Void> moveChain(@Validated @RequestBody ChainMoveBo bo) {
        return toAjax(directoryService.moveChain(bo.getChainId(), bo.getDirectoryId()));
    }

}
