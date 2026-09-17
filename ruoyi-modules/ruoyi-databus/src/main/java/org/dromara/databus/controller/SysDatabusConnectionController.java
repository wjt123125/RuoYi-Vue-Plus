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
import org.dromara.databus.domain.bo.SysDatabusConnectionBo;
import org.dromara.databus.domain.vo.SysDatabusConnectionVo;
import org.dromara.databus.service.ISysDatabusConnectionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

/**
 * 数据总线连接管理接口（连接管理页配套）。
 *
 * <p>端点：
 * <ul>
 *   <li>GET  /databus/connection/list          分页列表</li>
 *   <li>GET  /databus/connection/{id}          详情</li>
 *   <li>POST /databus/connection              新增</li>
 *   <li>PUT  /databus/connection              修改</li>
 *   <li>DELETE /databus/connection/{ids}       批量删除</li>
 *   <li>POST /databus/connection/test          测试连接（无需落库）</li>
 * </ul>
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/connection")
public class SysDatabusConnectionController extends BaseController {

    private final ISysDatabusConnectionService connectionService;

    /**
     * 分页查询连接列表。
     */
    @SaCheckPermission("databus:connection:list")
    @GetMapping("/list")
    public R<PageResult<SysDatabusConnectionVo>> list(SysDatabusConnectionBo bo, PageQuery pageQuery) {
        return R.ok(connectionService.queryPageList(bo, pageQuery));
    }

    /**
     * 连接详情。
     */
    @SaCheckPermission("databus:connection:query")
    @GetMapping("/{id}")
    public R<SysDatabusConnectionVo> getInfo(@NotNull(message = "主键不能为空")
                                              @PathVariable("id") Long id) {
        return R.ok(connectionService.queryById(id));
    }

    /**
     * 新增连接。
     */
    @SaCheckPermission("databus:connection:add")
    @Log(title = "数据总线连接", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated @RequestBody SysDatabusConnectionBo bo) {
        return toAjax(connectionService.insertByBo(bo));
    }

    /**
     * 修改连接。
     */
    @SaCheckPermission("databus:connection:edit")
    @Log(title = "数据总线连接", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated @RequestBody SysDatabusConnectionBo bo) {
        return toAjax(connectionService.updateByBo(bo));
    }

    /**
     * 批量删除连接。
     */
    @SaCheckPermission("databus:connection:remove")
    @Log(title = "数据总线连接", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(connectionService.deleteByIds(Arrays.asList(ids)));
    }

    /**
     * 测试连接（无需落库，前端"测试连接"按钮直接传当前表单值）。
     * <p>失败时返回 HTTP 200 + R.fail（错误信息在 msg 字段），便于前端统一展示。
     */
    @SaCheckPermission("databus:connection:test")
    @Log(title = "数据总线连接测试", businessType = BusinessType.OTHER)
    @PostMapping("/test")
    public R<String> test(@Validated @RequestBody SysDatabusConnectionBo bo) {
        return R.ok("测试成功", connectionService.testConnection(bo));
    }

}
