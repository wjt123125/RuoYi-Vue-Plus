package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.vo.DatabusComponentVo;

import java.util.Collection;
import java.util.List;

/**
 * 组件元信息 Service 接口
 *
 * @author databus
 */
public interface IDatabusComponentService {

    /**
     * 查询单个组件元信息
     *
     * @param id 主键
     * @return 组件元信息视图对象
     */
    DatabusComponentVo queryById(Long id);

    /**
     * 分页查询组件元信息列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<DatabusComponentVo> queryPageList(DatabusComponentBo bo, PageQuery pageQuery);

    /**
     * 查询全部启用的组件（编辑器组件面板物料用），按分类、创建时间排序
     *
     * @return 启用组件列表
     */
    List<DatabusComponentVo> queryEnabledList();

    /**
     * 新增组件元信息
     *
     * @param bo 组件元信息业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(DatabusComponentBo bo);

    /**
     * 修改组件元信息
     *
     * @param bo 组件元信息业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(DatabusComponentBo bo);

    /**
     * 批量删除组件元信息
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteByIds(Collection<Long> ids);

}
