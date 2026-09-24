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
import org.dromara.databus.domain.vo.ChainStatsVo;
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
     * 按 status 分组计数（链路管理页顶部统计块用）。
     * <p>
     * 分页 list 无法前端聚合准确计数，故补此轻量统计接口。
     */
    @SaCheckPermission("databus:editor:list")
    @GetMapping("/stats")
    public R<ChainStatsVo> stats() {
        return R.ok(chainService.countByStatus());
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

    /**
     * 发布链路：status 0草稿/2已下线 → 1已发布 + version+1
     * <p>
     * 草稿与发布共用一份 el_expression + canvas_data，发布即固化当前编排为运行版本；
     * 已下线链路可重新发布，version 继续递增。
     *
     * @param id 链路主键
     */
    @SaCheckPermission("databus:editor:publish")
    @Log(title = "数据总线链路", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/publish/{id}")
    public R<Void> publish(@NotNull(message = "主键不能为空")
                           @PathVariable("id") Long id) {
        return toAjax(chainService.publish(id));
    }

    /**
     * 下线链路：status 1已发布 → 2已下线
     * <p>
     * 下线后定义保留（不物理删除），可重新发布；下线状态不可执行。
     *
     * @param id 链路主键
     */
    @SaCheckPermission("databus:editor:offline")
    @Log(title = "数据总线链路", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/offline/{id}")
    public R<Void> offline(@NotNull(message = "主键不能为空")
                           @PathVariable("id") Long id) {
        return toAjax(chainService.offline(id));
    }

    /**
     * 复制链路：以源链路的画布与配置生成一条全新草稿
     * <p>
     * 新链路为草稿状态（不推 Rule-DB、不影响源链路），副本编码重新生成、名称加"副本"。
     *
     * @param id 源链路主键
     */
    @SaCheckPermission("databus:editor:add")
    @Log(title = "数据总线链路", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/copy/{id}")
    public R<Void> copy(@NotNull(message = "主键不能为空")
                        @PathVariable("id") Long id) {
        return toAjax(chainService.copy(id));
    }

}
