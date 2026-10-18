package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.DatabusFavoriteBo;
import org.dromara.databus.domain.vo.DatabusFavoriteVo;
import org.dromara.databus.service.IDatabusFavoriteService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户收藏接口（工作区统一资源树「我的收藏」）。
 * <p>
 * 收藏是个人化组织维度（目录是团队共享归属单真相）：三个端点 list/add/remove，
 * userId 一律从当前登录态取，前端只传 resourceType + resourceId。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/favorite")
public class DatabusFavoriteController extends BaseController {

    private final IDatabusFavoriteService favoriteService;

    /**
     * 查询当前用户收藏列表（平铺，前端组「我的收藏」section）
     */
    @SaCheckPermission("databus:favorite:list")
    @GetMapping("/list")
    public R<List<DatabusFavoriteVo>> list() {
        return R.ok(favoriteService.queryList(LoginHelper.getUserId()));
    }

    /**
     * 新增收藏（幂等：重复收藏不报错）
     */
    @SaCheckPermission("databus:favorite:add")
    @Log(title = "用户收藏", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated @RequestBody DatabusFavoriteBo bo) {
        return toAjax(favoriteService.add(LoginHelper.getUserId(), bo.getResourceType(), bo.getResourceId()));
    }

    /**
     * 取消收藏（幂等：未收藏也不报错）
     */
    @SaCheckPermission("databus:favorite:remove")
    @Log(title = "用户收藏", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping()
    public R<Void> remove(@Validated @RequestBody DatabusFavoriteBo bo) {
        return toAjax(favoriteService.remove(LoginHelper.getUserId(), bo.getResourceType(), bo.getResourceId()));
    }

}
