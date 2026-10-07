package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.bo.ScriptRollbackBo;
import org.dromara.databus.domain.bo.ScriptSaveBo;
import org.dromara.databus.domain.vo.ComponentDomainVo;
import org.dromara.databus.domain.vo.ComponentGroupVo;
import org.dromara.databus.domain.vo.ComponentOptionsVo;
import org.dromara.databus.domain.vo.DatabusComponentVersionVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.domain.vo.ScriptRuntimeVo;
import org.dromara.databus.domain.vo.ScriptSaveResultVo;

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
     * 查询编辑器物料合流选项（/options）：内置注解件（source=SYSTEM，按 sort）+
     * databus_component 启用行（source=CUSTOM，按 id），code 冲突内置优先、DB 行丢弃并 warn。
     *
     * @return 带 schemaVersion 的合流响应
     */
    ComponentOptionsVo queryOptions();

    /**
     * 查询物料面板分组字典（databus_component_group）：编辑器面板与台账树共用，
     * 不分页、不缓存（表内 <10 行，走唯一索引，缓存反引入「改了字典页面不变」的排障成本）。
     *
     * @return 分组字典列表（sort asc, id asc）
     */
    List<ComponentGroupVo> queryGroups();

    /**
     * 查询物料业务域字典（databus_component_domain）：含兜底域标记 isDefault，
     * domain 为空的普通叶子件由前端按该标记归入兜底域。不分页、不缓存。
     *
     * @return 业务域字典列表（sort asc, id asc）
     */
    List<ComponentDomainVo> queryDomains();

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

    /**
     * 保存脚本正文：保存即编译——编译/实例化失败整体不落库（事务回滚、FlowBus 不换）；
     * 成功则版本表追加一行、主表版本号+1、回填契约缓存列、热替换 FlowBus 节点。
     *
     * @param bo 脚本保存入参
     * @return 新版本号与物化契约
     */
    ScriptSaveResultVo saveScript(ScriptSaveBo bo);

    /**
     * 一键回滚：取目标版本源码重走保存管线，产生一条内容等同旧版的新版本行。
     *
     * @param bo 回滚入参
     * @return 新版本号与物化契约
     */
    ScriptSaveResultVo rollbackScript(ScriptRollbackBo bo);

    /**
     * 查询组件脚本版本历史（版本号倒序）。
     *
     * @param componentId 组件主键
     * @return 版本列表
     */
    List<DatabusComponentVersionVo> queryVersions(Long componentId);

    /**
     * 查询脚本组件运行时注册健康快照（失败件在前，供台账露出）。
     *
     * @return 健康列表
     */
    List<ScriptRuntimeVo> queryRuntimeHealth();

}
