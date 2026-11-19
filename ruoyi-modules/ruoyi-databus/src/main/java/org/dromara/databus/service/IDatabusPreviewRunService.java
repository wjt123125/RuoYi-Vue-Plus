package org.dromara.databus.service;

import org.dromara.databus.domain.bo.PreviewRunBo;
import org.dromara.databus.domain.vo.PreviewRunVo;

/**
 * 试运行服务：画布试跑与 AI 试跑共用同一实现（§3.12）。
 *
 * @author databus
 */
public interface IDatabusPreviewRunService {

    /**
     * 生成 EL 并执行一次试运行。
     *
     * @param bo 试运行入参（画布 JSON、请求 JSON、输入参数登记、草稿组件）
     * @return 试运行结果（只回前端，不落库）
     */
    PreviewRunVo run(PreviewRunBo bo);
}
