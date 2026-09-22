package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.DatabusChainVo;

import java.util.Collection;

/**
 * 链路定义 Service 接口
 *
 * @author databus
 */
public interface IDatabusChainService {

    /**
     * 查询单条链路（编辑器 load 用，含画布 JSON 与 EL 表达式）
     *
     * @param id 链路主键
     * @return 链路视图对象
     */
    DatabusChainVo queryById(Long id);

    /**
     * 分页查询链路列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<DatabusChainVo> queryPageList(DatabusChainBo bo, PageQuery pageQuery);

    /**
     * 新增链路草稿（EL 由后端从组件树生成，version=1/status=草稿）
     *
     * @param bo 链路业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(DatabusChainBo bo);

    /**
     * 修改链路草稿（EL 由后端从组件树重新生成）
     *
     * @param bo 链路业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(DatabusChainBo bo);

    /**
     * 批量删除链路
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteByIds(Collection<Long> ids);

    /**
     * 发布链路：status 0草稿/2已下线 → 1已发布 + version+1
     * <p>
     * 草稿与发布共用一份 el_expression + canvas_data，发布即固化当前编排为运行版本。
     * 重新发布（已下线 → 已发布）也走本方法，version 继续递增。
     *
     * @param id 链路主键
     * @return 是否发布成功
     */
    Boolean publish(Long id);

    /**
     * 下线链路：status 1已发布 → 2已下线
     * <p>
     * 下线后定义保留（不物理删除），可重新发布。下线状态不可执行。
     *
     * @param id 链路主键
     * @return 是否下线成功
     */
    Boolean offline(Long id);

}
