package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.domain.bo.SysDatabusConnectionBo;
import org.dromara.databus.domain.vo.SysDatabusConnectionVo;

import java.util.Collection;
import java.util.List;

/**
 * 数据总线连接管理 Service 接口。
 *
 * <p>职责：
 * <ul>
 *   <li>CRUD sys_databus_connection 表</li>
 *   <li>{@link #testConnection(SysDatabusConnectionBo)} 调对应 Connector 实测连通性</li>
 *   <li>{@link #loadEnabledConnections()} 把启用的连接转运行时 {@link Connection} 列表，
 *       供 {@code DatabusExecutor} 在每次执行链路前注入到 {@code DatabusContext}</li>
 * </ul>
 *
 * @author databus
 */
public interface ISysDatabusConnectionService {

    /**
     * 查询单条连接详情。
     */
    SysDatabusConnectionVo queryById(Long id);

    /**
     * 分页查询连接列表（支持 connectionId / connectionName / connectorType / enabled 模糊与等值过滤）。
     */
    PageResult<SysDatabusConnectionVo> queryPageList(SysDatabusConnectionBo bo, PageQuery pageQuery);

    /**
     * 新增连接。connectionId 在未删除记录中必须唯一。
     */
    Boolean insertByBo(SysDatabusConnectionBo bo);

    /**
     * 修改连接。connectionId 修改时也要校验唯一。
     */
    Boolean updateByBo(SysDatabusConnectionBo bo);

    /**
     * 批量删除连接（逻辑删除）。
     */
    Boolean deleteByIds(Collection<Long> ids);

    /**
     * 测试连接是否可用。
     * <p>按 BO 中的 connectorType 从 {@link org.dromara.databus.connector.ConnectorRegistry}
     * 取 Connector，把 BO 字段平铺转换为运行时 {@link Connection}，调
     * {@link org.dromara.databus.connector.Connector#testConnection(Connection)} 实测。
     *
     * @param bo 连接配置（无需先落库，前端"测试连接"按钮直接传当前表单值）
     * @return Connector 返回的成功提示信息
     */
    String testConnection(SysDatabusConnectionBo bo);

    /**
     * 把启用的连接（enabled=Y）从 DB 加载并转换为运行时 {@link Connection} 列表。
     * <p>由 {@code DatabusExecutor} 在每次执行链路前调用，逐个
     * {@code DatabusContext.registerConnection(conn)} 注入到当前执行上下文。
     * <p>性能注意：每次执行都查一次 DB；后续如有性能压力可加缓存。
     */
    List<Connection> loadEnabledConnections();

}
