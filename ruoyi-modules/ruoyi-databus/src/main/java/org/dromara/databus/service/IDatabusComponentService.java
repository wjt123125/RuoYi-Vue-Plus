package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.vo.ComponentOptionsVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;

import java.util.Collection;

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
     * 查询编辑器物料合流选项（/options）：内置注解件（source=SYSTEM，按 sort）+
     * databus_component 启用行（source=CUSTOM，按 id），code 冲突内置优先、DB 行丢弃并 warn。
     *
     * @return 带 schemaVersion 的合流响应
     */
    ComponentOptionsVo queryOptions();

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
