package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusAlertRecordBo;
import org.dromara.databus.domain.bo.DatabusAlertRuleBo;
import org.dromara.databus.domain.vo.AlertEvaluateVo;
import org.dromara.databus.domain.vo.DatabusAlertRecordVo;
import org.dromara.databus.domain.vo.DatabusAlertRuleVo;

import java.util.Collection;

/**
 * 数据总线告警引擎 Service（阶段5 监控体系）。
 * <p>
 * 两块职责：
 * <ul>
 *   <li>规则 CRUD + 告警记录只读查询（前端告警管理页消费）；</li>
 *   <li>{@link #evaluate()} 评估：扫描启用规则，按类型判定窗口内执行表现，命中即触发一条
 *       FIRING 记录并发邮件通知，恢复时翻转该记录为 RECOVERED。定时任务与手动端点共用本入口。</li>
 * </ul>
 *
 * @author databus
 */
public interface IDatabusAlertService {

    /**
     * 分页查询告警规则。
     *
     * @param bo        查询条件（规则名/链路编码模糊，类型/启用精确）
     * @param pageQuery 分页参数
     * @return 告警规则分页
     */
    PageResult<DatabusAlertRuleVo> queryRulePageList(DatabusAlertRuleBo bo, PageQuery pageQuery);

    /**
     * 查询单条告警规则。
     *
     * @param id 主键
     * @return 告警规则，不存在返回 null
     */
    DatabusAlertRuleVo queryRuleById(Long id);

    /**
     * 新增告警规则。
     *
     * @param bo 规则业务对象
     * @return 是否成功
     */
    Boolean insertRule(DatabusAlertRuleBo bo);

    /**
     * 修改告警规则。
     *
     * @param bo 规则业务对象（含主键）
     * @return 是否成功
     */
    Boolean updateRule(DatabusAlertRuleBo bo);

    /**
     * 批量删除告警规则（记录表保留、不级联删除，便于历史追溯）。
     *
     * @param ids 主键集合
     * @return 是否成功
     */
    Boolean deleteRuleByIds(Collection<Long> ids);

    /**
     * 分页查询告警记录。
     *
     * @param bo        查询条件（规则名/链路编码模糊，类型/状态精确，触发时间区间）
     * @param pageQuery 分页参数
     * @return 告警记录分页
     */
    PageResult<DatabusAlertRecordVo> queryRecordPageList(DatabusAlertRecordBo bo, PageQuery pageQuery);

    /**
     * 评估全部启用规则：命中即触发（插入 FIRING 记录并通知），恢复即翻转最近 FIRING 记录。
     * <p>每条规则独立 try/catch，单条异常不阻断其余规则；返回本次扫描汇总供端点/日志观测。
     *
     * @return 评估结果汇总
     */
    AlertEvaluateVo evaluate();

    /**
     * 只评估指定链路的启用规则（事件驱动入口：链路执行落账后由
     * {@code AlertEvaluateTrigger} 异步调用，做到失败秒级告警）。
     * <p>判定逻辑、状态机去重与 {@link #evaluate()} 完全一致；与分钟级定时评估并发时
     * 按规则串行，最坏重复评估不会重复通知。
     *
     * @param chainCode 链路编码（空白直接返回空汇总）
     * @return 评估结果汇总
     */
    AlertEvaluateVo evaluateChain(String chainCode);

}