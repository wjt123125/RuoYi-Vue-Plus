package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusExecution;
import org.dromara.databus.domain.vo.DatabusExecutionVo;

import java.util.Collection;
import java.util.List;

/**
 * 链路执行记录 Mapper 接口
 *
 * @author databus
 */
public interface DatabusExecutionMapper extends BaseMapperPlus<DatabusExecution, DatabusExecutionVo> {

    /**
     * 批量取每条链路最近一次执行记录（只回 chain_id / status），供「已装管理」标注运行健康。
     * <p>
     * 用相关子查询而不是窗口函数：MySQL 5.7 / PostgreSQL 都能跑；一次安装条数按用户量级，
     * 不会把整张执行记录表扫成 N+1。
     */
    @Select("""
        <script>
        SELECT e.chain_id AS chainId, e.status AS status
        FROM databus_execution e
        WHERE e.chain_id IN
        <foreach collection="chainIds" item="chainId" open="(" separator="," close=")">#{chainId}</foreach>
          AND e.id = (
            SELECT e2.id FROM databus_execution e2
            WHERE e2.chain_id = e.chain_id
            ORDER BY e2.start_time DESC, e2.id DESC LIMIT 1
          )
        </script>
        """)
    List<DatabusExecution> selectLatestByChainIds(@Param("chainIds") Collection<Long> chainIds);
}
