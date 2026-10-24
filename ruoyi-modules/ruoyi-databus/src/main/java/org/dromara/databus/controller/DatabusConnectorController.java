package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.vo.DatabusConnectorDefVo;
import org.dromara.databus.service.IDatabusConnectorService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 连接器运行层接口（万物皆包，databus-material-market.md §15.7 四端点）。
 * <p>
 * 前两组对齐市场 tab 内上传入口（不另设独立包管理页，§15.3）：
 * <ul>
 *   <li>上传：POST /databus/connector/packages（zip 校验+落盘+入库；同 type 升版本＝热更新）</li>
 *   <li>视图：GET /databus/connector/defs（内置∪def 合并；连接管理页 CONNECTOR_OPTIONS 动态化）</li>
 *   <li>启停：POST /databus/connector/defs/{type}/enable | /disable（运行层状态机）</li>
 *   <li>卸载：DELETE /databus/connector/defs/{type}（默认拒绝在用连接；?force=true 强制）</li>
 * </ul>
 * 上传者身份从登录态取，前端不传。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/connector")
public class DatabusConnectorController extends BaseController {

    private final IDatabusConnectorService connectorService;

    /**
     * 上传连接器包（zip＝manifest.json + lib/）：校验 + 落盘 + 入库。
     * 新 type 默认 SUSPENDED（审核即信任），enable 是治理者显式动作。
     */
    @SaCheckPermission("databus:connector:upload")
    @Log(title = "连接器包上传", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/packages")
    public R<DatabusConnectorDefVo> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(connectorService.upload(file, LoginHelper.getUserId()));
    }

    /**
     * def 合并视图（内置 ∪ def 行，含 status；驱动连接管理页下拉与连接器卡片）。
     */
    @SaCheckPermission("databus:connector:list")
    @GetMapping("/defs")
    public R<List<DatabusConnectorDefVo>> defs() {
        return R.ok(connectorService.listDefs());
    }

    /**
     * 启用（ENABLED：进 defs 可选列表；v1 运行时加载为后续分期）。
     */
    @SaCheckPermission("databus:connector:edit")
    @Log(title = "连接器启用", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/defs/{type}/enable")
    public R<Void> enable(@NotBlank(message = "类型不能为空") @PathVariable("type") String type) {
        return toAjax(connectorService.enable(type));
    }

    /**
     * 暂停（SUSPENDED：挡新建连接，存量 Connection 与数据保留）。
     */
    @SaCheckPermission("databus:connector:edit")
    @Log(title = "连接器暂停", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/defs/{type}/disable")
    public R<Void> disable(@NotBlank(message = "类型不能为空") @PathVariable("type") String type) {
        return toAjax(connectorService.disable(type));
    }

    /**
     * 卸载（默认拒绝在用连接；force=true 管理员强制，存量连接悬空自然衰减）。
     */
    @SaCheckPermission("databus:connector:remove")
    @Log(title = "连接器卸载", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/defs/{type}")
    public R<Void> uninstall(@NotBlank(message = "类型不能为空") @PathVariable("type") String type,
                             @RequestParam(value = "force", required = false, defaultValue = "false") Boolean force) {
        return toAjax(connectorService.uninstall(type, Boolean.TRUE.equals(force)));
    }

}
