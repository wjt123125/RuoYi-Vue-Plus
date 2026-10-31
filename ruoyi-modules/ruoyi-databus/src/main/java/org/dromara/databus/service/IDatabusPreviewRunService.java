package org.dromara.databus.service;

import org.dromara.databus.domain.bo.PreviewRunBo;
import org.dromara.databus.domain.vo.PreviewRunVo;

/**
 * 试运行服务：抽取 {@code DatabusEditorController} 试运行六步（§3.12）为可调用服务，
 * AI 工具与人工界面共用。
 *
 * @author databus
 */
public interface IDatabusPreviewRunService {

    /**
     * 基于当前画布内容生成 EL、语法校验通过后按 EL 真执行（不落库），返回校验结果与执行结果。
     * 执行失败也正常返回（HTTP 200），错误信息在 {@link PreviewRunVo#getErrorMessage()} 中。
     *
     * @param bo 画布组件树 + 执行入参 JSON + 链路入参登记
     * @return EL 表达式、校验结果与执行结果
     */
    PreviewRunVo run(PreviewRunBo bo);
}