package org.dromara.databus.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.context.InputParamValidator;
import org.dromara.databus.context.JsonCodec;
import org.dromara.databus.domain.bo.PreviewRunBo;
import org.dromara.databus.domain.vo.PreviewRunVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.executor.DatabusExecutionResult;
import org.dromara.databus.executor.DatabusExecutor;
import org.dromara.databus.service.IDatabusPreviewRunService;
import org.springframework.stereotype.Service;

/**
 * 试运行服务唯一实现：六步与抽取前 Controller 行为逐字一致（§3.12）。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DatabusPreviewRunServiceImpl implements IDatabusPreviewRunService {

    private final ExpressGenerator expressGenerator;

    private final DatabusExecutor databusExecutor;

    @Override
    public PreviewRunVo run(PreviewRunBo bo) {
        CmpProperty jsonEl = bo.getJsonEl();
        String elStr = null;
        try {
            // 脚本节点（script/booleanScript）没有 @LiteflowComponent，EL 引用的 nodeId 必须先在
            // FlowBus 中注册才能通过 LiteFlowChainELBuilder.validate / setEL 编译；故在 generateEL
            // 之前先把画布上的脚本节点预注册（其他业务组件已由 Spring 扫描自动注册）。
            databusExecutor.registerScriptNodes(jsonEl);

            ELInfo elInfo = expressGenerator.generateEL(jsonEl);
            elStr = elInfo == null ? null : elInfo.getElStr();
            String elError = expressGenerator.validateEL(jsonEl);
            if (elError != null) {
                log.error("[databus-preview-run] EL 校验未通过：{}", elError);
                return PreviewRunVo.fail(elStr, "EL 表达式校验失败：" + elError);
            }

            Object requestData = JsonCodec.parse(bo.getRequestJson());
            // 必填校验：默认值不注入试运行请求（前端已按默认值预填，这里只校验最终入参）
            InputParamValidator.validateRequired(bo.getInputParams(), requestData);
            DatabusExecutionResult executionResult = databusExecutor.executeByEl(elStr, requestData, jsonEl);
            return PreviewRunVo.executed(elStr, executionResult);
        } catch (Exception e) {
            log.warn("preview-run 生成/执行失败: {}", e.getMessage());
            return PreviewRunVo.fail(elStr, "试运行失败：" + e.getMessage());
        }
    }
}