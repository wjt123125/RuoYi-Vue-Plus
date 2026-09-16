package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画布 JSON 与 LiteFlow EL 表达式互转接口。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/el")
public class ElGenerateController extends BaseController {

    private final ExpressGenerator expressGenerator;

    /**
     * 画布 JSON 结构转 EL 表达式。
     *
     * @param jsonEl 画布组件树（CmpProperty）
     * @return EL 表达式信息
     */
    @SaCheckPermission("databus:editor:add")
    @PostMapping("/generate")
    public R<ELInfo> generate(@RequestBody CmpProperty jsonEl) {
        return R.ok(expressGenerator.generateEL(jsonEl));
    }

}
