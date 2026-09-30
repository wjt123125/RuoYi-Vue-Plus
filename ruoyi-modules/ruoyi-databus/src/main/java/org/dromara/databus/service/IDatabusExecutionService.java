package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusExecutionBo;
import org.dromara.databus.domain.bo.ManualExecuteBo;
import org.dromara.databus.domain.vo.ExecutionCleanupVo;
import org.dromara.databus.domain.vo.ExecutionDetailVo;
import org.dromara.databus.domain.vo.DatabusExecutionVo;
import org.dromara.databus.executor.DatabusExecutionResult;

/**
 * 链路执行记录 Service 接口（设计档 §4.2/§4.5）。
 * <p>
 * 记录只增不改：不提供业务修改/逐条删除，记录的产生走执行器追踪牌 + 框架钩子，
 * 不经过本服务写入；唯一删除入口是 {@link #cleanup(Integer)} 保留期物理清理
 * （定时任务与手动端点共用）。
 *
 * @author databus
 */
public interface IDatabusExecutionService {

    /**
     * 分页查询执行记录（列表白名单不带 request_data/response_data 大字段）。
     *
     * @param bo        查询条件（链路编码/状态/开始时间区间）
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<DatabusExecutionVo> queryPageList(DatabusExecutionBo bo, PageQuery pageQuery);

    /**
     * 查询执行记录详情：总账 + 节点明细行（FULL 档）。
     *
     * @param id 执行记录主键
     * @return 详情（记录不存在抛 ServiceException）
     */
    ExecutionDetailVo queryDetail(Long id);

    /**
     * 手动执行已发布链路（设计档 §4.5）：校验 status=已发布后走正式 {@code execute()}，
     * 按链路 log_level 产生一条新执行记录。
     *
     * @param bo 链路id + 执行入参 JSON
     * @return 执行结果（含新记录 recordId；OFF 档无记录时为 null）
     */
    DatabusExecutionResult manualExecute(ManualExecuteBo bo);

    /**
     * 重跑：取历史记录的 chainCode + request_data，校验链路仍为已发布后走正式
     * {@code execute()}，产生一条新执行记录（原记录保持不变）。
     *
     * @param id 历史执行记录主键
     * @return 新一次执行结果（含新记录 recordId）
     */
    DatabusExecutionResult rerun(Long id);

    /**
     * 物理清理过期执行记录（定时任务与手动端点共用）：总账 start_time 早于
     * now - 保留天数 的记录整批删除，先删节点明细后删总账，分批循环防长事务。
     *
     * @param retentionDaysOverride 保留天数覆盖；null 取配置 databus.execution.cleanup.retention-days
     * @return 删除计数（总账/明细）与是否达到单轮上限被截断
     */
    ExecutionCleanupVo cleanup(Integer retentionDaysOverride);

}
