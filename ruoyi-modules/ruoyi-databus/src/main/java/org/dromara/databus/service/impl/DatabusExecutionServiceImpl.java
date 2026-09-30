package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.context.JsonCodec;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusExecution;
import org.dromara.databus.domain.DatabusExecutionNode;
import org.dromara.databus.domain.bo.DatabusExecutionBo;
import org.dromara.databus.domain.bo.ManualExecuteBo;
import org.dromara.databus.domain.vo.DatabusExecutionNodeVo;
import org.dromara.databus.domain.vo.DatabusExecutionVo;
import org.dromara.databus.domain.vo.ExecutionDetailVo;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.executor.DatabusExecutionResult;
import org.dromara.databus.executor.DatabusExecutor;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusExecutionMapper;
import org.dromara.databus.mapper.DatabusExecutionNodeMapper;
import org.dromara.databus.service.IDatabusExecutionService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 链路执行记录 Service 实现。
 * <p>
 * 只读查询 + 两个执行入口（手动执行/重跑，设计档 §4.5）。记录写入不在这里——
 * 执行器挂追踪牌、afterFlow 钩子落库；本服务的执行入口只负责「校验已发布 + 调正式通道」。
 * 校验红线：草稿/已下线链路一律拦截，避免不经发布的编排被跑出正式数据。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusExecutionServiceImpl implements IDatabusExecutionService {

    private final DatabusExecutionMapper executionMapper;

    private final DatabusExecutionNodeMapper nodeMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusExecutor databusExecutor;

    @Override
    public PageResult<DatabusExecutionVo> queryPageList(DatabusExecutionBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<DatabusExecution> lqw = Wrappers.lambdaQuery();
        // 列表白名单：排除 request_data / response_data 两个 longtext 大字段（列表零消费），
        // error_msg 保留（失败列表行内摘要展示）；详情接口取全量。
        lqw.select(DatabusExecution::getId, DatabusExecution::getChainId, DatabusExecution::getChainCode,
            DatabusExecution::getStatus, DatabusExecution::getErrorMsg,
            DatabusExecution::getStartTime, DatabusExecution::getEndTime, DatabusExecution::getDuration,
            DatabusExecution::getCreateTime);
        lqw.like(StringUtils.isNotBlank(bo.getChainCode()), DatabusExecution::getChainCode, bo.getChainCode());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), DatabusExecution::getStatus, bo.getStatus());
        lqw.between(StringUtils.isNotBlank(bo.getBeginTime()) && StringUtils.isNotBlank(bo.getEndTime()),
            DatabusExecution::getStartTime, bo.getBeginTime(), bo.getEndTime());
        lqw.orderByDesc(DatabusExecution::getStartTime)
            .orderByDesc(DatabusExecution::getId);
        Page<DatabusExecutionVo> result = executionMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public ExecutionDetailVo queryDetail(Long id) {
        DatabusExecutionVo execution = executionMapper.selectVoById(id);
        if (execution == null) {
            throw new ServiceException("执行记录不存在或已被清理");
        }
        ExecutionDetailVo detail = new ExecutionDetailVo();
        detail.setExecution(execution);
        // 节点行按开始时间升序还原执行时序（WHEN 并行行开始时间相同，再按插入 id 兜底稳定排序）
        List<DatabusExecutionNodeVo> nodes = nodeMapper.selectVoList(
            Wrappers.<DatabusExecutionNode>lambdaQuery()
                .eq(DatabusExecutionNode::getExecutionId, id)
                .orderByAsc(DatabusExecutionNode::getStartTime)
                .orderByAsc(DatabusExecutionNode::getId));
        detail.setNodes(nodes);
        return detail;
    }

    @Override
    public DatabusExecutionResult manualExecute(ManualExecuteBo bo) {
        DatabusChain chain = chainMapper.selectById(bo.getChainId());
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        assertPublished(chain);
        Object requestData = parseRequestJson(bo.getRequestJson());
        return databusExecutor.execute(chain.getChainCode(), requestData);
    }

    @Override
    public DatabusExecutionResult rerun(Long id) {
        DatabusExecution record = executionMapper.selectById(id);
        if (record == null) {
            throw new ServiceException("执行记录不存在或已被清理");
        }
        DatabusChain chain = chainMapper.selectOne(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, record.getChainCode()));
        if (chain == null) {
            throw new ServiceException("链路'" + record.getChainCode() + "'已不存在，无法重跑");
        }
        assertPublished(chain);
        // 原样取历史入参（OFF 档没有记录故不会走到这里；request_data 理论非空）
        Object requestData = parseRequestJson(record.getRequestData());
        return databusExecutor.execute(chain.getChainCode(), requestData);
    }

    /**
     * 已发布校验红线：仅 status=1 可从手动执行/重跑入口走正式通道。
     */
    private void assertPublished(DatabusChain chain) {
        if (!ChainStatusEnum.PUBLISHED.getCode().equals(chain.getStatus())) {
            throw new ServiceException("链路'" + chain.getChainName() + "'不是已发布状态，不能执行（请先发布）");
        }
    }

    /**
     * 严格解析入参 JSON：空白 → null（空文档执行）；非法 JSON 直接拦截，
     * 不沿用 JsonCodec.parse 的静默返回 null（那会让用户带着错误入参执行却不自知）。
     */
    private Object parseRequestJson(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JsonCodec.parseStrict(json);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(e.getMessage());
        }
    }

}
