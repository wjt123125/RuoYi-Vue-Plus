package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.MarketInstallBo;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.domain.bo.MarketQueryBo;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.domain.vo.DatabusMarketCategoryVo;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.domain.vo.DatabusMarketItemVo;
import org.dromara.databus.service.IDatabusMarketService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 物料市场接口（组件 / 链路市场；工作台内「市场」tab 的唯一后端）。
 * <p>
 * 设计正本 databus-material-market.md §十一 的三组端点：
 * <ul>
 *   <li>浏览（只读）：GET /databus/market/items（分页）、/categories、/items/{id}</li>
 *   <li>消费（安装侧）：POST /items/{id}/install、DELETE /install/{resourceId}、GET /installed</li>
 *   <li>供给（上架侧）：POST /publish、POST /items/{id}/unpublish、POST /items/{id}/review、DELETE /items/{id}</li>
 * </ul>
 * userId / ownerCode 一律从当前登录态取，前端不传身份字段。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/market")
public class DatabusMarketController extends BaseController {

    private final IDatabusMarketService marketService;

    /* ---------------- 浏览（只读） ---------------- */

    /**
     * 市场物料列表（类型 / 分类 / 关键字过滤 + 分页；回填当前用户是否已装）。
     */
    @SaCheckPermission("databus:market:list")
    @GetMapping("/items")
    public R<PageResult<DatabusMarketItemVo>> items(MarketQueryBo bo, PageQuery pageQuery) {
        return R.ok(marketService.queryPageList(bo, pageQuery, LoginHelper.getUserId()));
    }

    /**
     * 分类列表（多级平表，含在线物料计数）。
     */
    @SaCheckPermission("databus:market:list")
    @GetMapping("/categories")
    public R<List<DatabusMarketCategoryVo>> categories() {
        return R.ok(marketService.queryCategories());
    }

    /**
     * 物料详情（在线人人可见；非在线仅作者本人可见）。
     */
    @SaCheckPermission("databus:market:list")
    @GetMapping("/items/{id}")
    public R<DatabusMarketItemVo> getInfo(@NotNull(message = "主键不能为空")
                                   @PathVariable("id") Long id) {
        return R.ok(marketService.queryById(id, LoginHelper.getUserId()));
    }

    /* ---------------- 消费（安装侧） ---------------- */

    /**
     * 安装＝fork（复制快照进我的目录 + 记血缘）。
     */
    @SaCheckPermission("databus:market:install")
    @Log(title = "物料市场安装", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/items/{id}/install")
    public R<DatabusMarketInstallVo> install(@NotNull(message = "主键不能为空")
                                      @PathVariable("id") Long id,
                                      @RequestBody(required = false) MarketInstallBo bo) {
        Long directoryId = bo == null ? null : bo.getDirectoryId();
        return R.ok(marketService.install(id, directoryId, LoginHelper.getUserId()));
    }

    /**
     * 卸载（删我的本地副本 + 删血缘；不硬拦引用）。
     */
    @SaCheckPermission("databus:market:uninstall")
    @Log(title = "物料市场卸载", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/install/{resourceId}")
    public R<Void> uninstall(@NotNull(message = "资源id不能为空")
                             @PathVariable("resourceId") Long resourceId) {
        return toAjax(marketService.uninstall(resourceId, LoginHelper.getUserId()));
    }

    /**
     * 我的已装列表（管理页数据源）。
     */
    @SaCheckPermission("databus:market:list")
    @GetMapping("/installed")
    public R<List<DatabusMarketInstallVo>> installed() {
        return R.ok(marketService.queryInstalled(LoginHelper.getUserId()));
    }

    /**
     * 我的上架列表（作者视角治理视图，含各状态）。
     */
    @SaCheckPermission("databus:market:publish")
    @GetMapping("/mine")
    public R<List<DatabusMarketItemVo>> mine() {
        return R.ok(marketService.queryMine(LoginHelper.getUserId()));
    }

    /**
     * 待审核列表（治理者视角，仅 PENDING）。
     */
    @SaCheckPermission("databus:market:review")
    @GetMapping("/pending")
    public R<List<DatabusMarketItemVo>> pending() {
        return R.ok(marketService.queryPending());
    }

    /* ---------------- 供给（上架侧） ---------------- */

    /**
     * 提交上架（进待审；同源重复提交则更新回待审）。
     */
    @SaCheckPermission("databus:market:publish")
    @Log(title = "物料市场上架", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/publish")
    public R<Void> publish(@Validated @RequestBody MarketPublishBo bo) {
        return toAjax(marketService.publish(bo, LoginHelper.getUserId(), LoginHelper.getUsername()));
    }

    /**
     * 下架（在线 → 下线；仅作者本人）。
     */
    @SaCheckPermission("databus:market:publish")
    @Log(title = "物料市场下架", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/items/{id}/unpublish")
    public R<Void> unpublish(@NotNull(message = "主键不能为空")
                             @PathVariable("id") Long id) {
        return toAjax(marketService.unpublish(id, LoginHelper.getUserId()));
    }

    /**
     * 审核（通过 → 上线 / 驳回 → 驳回态）。
     */
    @SaCheckPermission("databus:market:review")
    @Log(title = "物料市场审核", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/items/{id}/review")
    public R<Void> review(@NotNull(message = "主键不能为空")
                          @PathVariable("id") Long id,
                          @Validated @RequestBody MarketReviewBo bo) {
        return toAjax(marketService.review(id, bo, LoginHelper.getUserId()));
    }

    /**
     * 删市场原件（作者主权；与卸载分离）。
     */
    @SaCheckPermission("databus:market:remove")
    @Log(title = "物料市场删除", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/items/{id}")
    public R<Void> remove(@NotNull(message = "主键不能为空")
                          @PathVariable("id") Long id) {
        return toAjax(marketService.deleteItem(id, LoginHelper.getUserId()));
    }

}