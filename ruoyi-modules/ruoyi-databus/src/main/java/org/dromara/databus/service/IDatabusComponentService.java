package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.bo.ScriptDraftBo;
import org.dromara.databus.domain.bo.ScriptProbeBo;
import org.dromara.databus.domain.bo.ScriptRollbackBo;
import org.dromara.databus.domain.bo.ScriptSaveBo;
import org.dromara.databus.domain.vo.ComponentDomainVo;
import org.dromara.databus.domain.vo.ComponentGroupVo;
import org.dromara.databus.domain.vo.ComponentOptionsVo;
import org.dromara.databus.domain.vo.DatabusComponentVersionVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.domain.vo.ScriptDraftResultVo;
import org.dromara.databus.domain.vo.ScriptProbeResultVo;
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
     * 修改组件元信息（不含启停状态：status 由 {@link #updateStatus} 独立权限点写）
     *
     * @param bo 组件元信息业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(DatabusComponentBo bo);

    /**
     * 启停组件（治理专属，独立权限点 {@code databus:component:toggle}）。
     * <p>与「修改组件」拆权限：持有 {@code databus:component:edit} 的操作员能改元信息，
     * 但不能顺手停用/启用物料（停用会让编辑器面板不再投放该件）。
     *
     * @param id     组件主键
     * @param status 目标状态（0启用 1停用）
     * @return 是否修改成功
     */
    Boolean updateStatus(Long id, String status);

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

    /**
     * 落脚本草稿：编译通过才建草稿行（status=DRAFT），但不注册 FlowBus、不进物料面板。
     * <p>草稿归发起人所有，只有本人能改；生效必须由人在审阅页点采纳（{@link #adoptScriptDraft}）。
     *
     * @param bo 草稿入参（createBy 由服务端按会话身份注入）
     * @return 草稿行信息与物化契约
     */
    ScriptDraftResultVo saveScriptDraft(ScriptDraftBo bo);

    /**
     * 采纳脚本草稿：校验草稿态 → 先转 ENABLED → 再走一遍 {@link #saveScript} 保存管线。
     * <p>同一事务：编译/实例化/注册任一步失败，状态与版本一起回滚，不会留下「已启用但跑不起来」的件。
     *
     * @param id 草稿组件主键
     * @return 新版本号与物化契约
     */
    ScriptSaveResultVo adoptScriptDraft(Long id);

    /**
     * 组件级探针试跑：编译源码 → 造实例 → 用临时节点 id 注进 FlowBus → 单节点执行 → 立刻摘除。
     * <p>不建链、不落库、不写执行记录；用于 AI 在落草稿前验证「组件本身能不能跑」。
     *
     * @param bo 探针入参
     * @return 试跑结果
     */
    ScriptProbeResultVo probeRun(ScriptProbeBo bo);

    /**
     * 拒绝草稿：人在审阅页点「拒绝」时物理删掉 DRAFT 行。
     * <p>草稿从未注册 FlowBus、未被任何链路引用，删除无副作用；非草稿行一律拒绝操作。
     *
     * @param id 草稿组件主键
     * @return 是否删除成功
     */
    Boolean rejectScriptDraft(Long id);

}
