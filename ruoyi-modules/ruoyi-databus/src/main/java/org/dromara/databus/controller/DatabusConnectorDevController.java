package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.connector.plugin.DevConnectorLoader;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 连接器 dev 目录装载重载接口（方案 §5.2，仅 dev profile 生效）。
 *
 * <p>验证过程三步：IDE 改代码 → 编译 → 调一次 reload，几秒钟。
 *
 * <p>{@code @Profile("dev")} 与 {@link DevConnectorLoader} 同口径（§5.3 第 1 条）：
 * profile 不是 dev 时 Spring 不注册本 Controller，接口地址<b>不存在</b>，
 * 不是「地址存在但返回拒绝」。
 *
 * <p>审计（§5.3 第 4 条）：{@code @Log} 落操作人 / 时间 / 请求参数（type）到操作日志。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@Profile("dev")
@RequestMapping("/databus/connector/dev")
public class DatabusConnectorDevController extends BaseController {

    private final DevConnectorLoader devConnectorLoader;

    /**
     * 热重载：卸载旧实例（§4.3 四步）→ 重新读目录 → 装载新实例。
     *
     * @param type 连接器类型；留空表示重载 {@code dev-plugin-dir} 配置的全部目录
     * @return 本次重载成功的 type 列表
     */
    @SaCheckPermission("databus:connector:edit")
    @Log(title = "连接器 dev 重载", businessType = BusinessType.UPDATE)
    @PostMapping("/reload")
    public R<List<String>> reload(@RequestParam(value = "type", required = false) String type) {
        return R.ok(devConnectorLoader.reload(type));
    }

}
