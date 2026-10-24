package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.bo.ChainCopyBo;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.bo.TemplateMarkBo;
import org.dromara.databus.domain.vo.ChainPublishResultVo;
import org.dromara.databus.domain.vo.ChainStatsVo;
import org.dromara.databus.domain.vo.CopySuggestionVo;
import org.dromara.databus.domain.vo.DatabusChainVo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import org.dromara.databus.el.bean.CmpProperty;

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
     * @param bo        查询条件（onlyMine=true 时叠加 create_by 过滤）
     * @param pageQuery 分页参数
     * @param userId    当前登录用户（onlyMine 过滤用，超级管理员豁免）
     * @return 分页结果
     */
    PageResult<DatabusChainVo> queryPageList(DatabusChainBo bo, PageQuery pageQuery, Long userId);

    /**
     * 按 status 分组计数（链路管理页顶部统计块用）。
     * <p>
     * 分页 list 无法前端聚合准确计数，故补此轻量统计接口。
     *
     * @return 总数 / 草稿 / 已发布 / 已下线 计数
     */
    ChainStatsVo countByStatus();

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
     * @param bo     链路业务对象
     * @param userId 当前用户 id（编辑归属校验：非作者拒改）
     * @return 是否修改成功
     */
    Boolean updateByBo(DatabusChainBo bo, Long userId);

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
     * <p>
     * 发布侧引导（decisions §十四-6）：闭包内他人的未上架链直接拒绝（无权代提）；
     * 自己的未上架物料（子链＋组件）随发布结果返回引导清单，供前端弹窗确认后
     * 批量提审（POST /databus/market/publish/batch）。改版冻结钩子见 decisions §十四-8。
     *
     * @param id     链路主键
     * @param userId 发布操作者（判闭包依赖「自己的/他人的」）
     * @return 引导清单＋他人未上架组件提示；发布失败（理论不可达）返回 null
     */
    ChainPublishResultVo publish(Long id, Long userId);

    /**
     * 下线链路：status 1已发布 → 2已下线
     * <p>
     * 下线后定义保留（不物理删除），可重新发布。下线状态不可执行。
     *
     * @param id 链路主键
     * @return 是否下线成功
     */
    Boolean offline(Long id);

    /**
     * 复制链路：以源链路的画布与组件配置生成一条全新草稿。
     * <p>
     * 新链路 status=草稿、version=1，名称与编码由用户在复制弹窗确认（编码为副本终身身份，
     * 走唯一性校验）；canvas_data / cmp_property / log_level / input_params 原样复制，
     * 引用的连接器仅复制 connectionId 引用、不复制连接器本身；草稿不推 Rule-DB，
     * EL 由组件树按新增口径重新生成。源链路若为模板，副本一律为普通链路（剥离模板三字段）。
     *
     * @param id 源链路主键
     * @param bo 副本名称/编码
     * @return 新链路主键（供「使用模板」复制后直跳编辑器）
     */
    Long copy(Long id, ChainCopyBo bo);

    /**
     * 复制建议值：名称建议「源名称+副本」；编码在源编码（剥离既有 _N 尾缀）基础上
     * 从 _2 起查库取首个未占用值，供复制弹窗预填。
     *
     * @param id 源链路主键
     * @return 名称/编码建议
     */
    CopySuggestionVo getCopySuggestion(Long id);

    /**
     * 设为精选模板：写模板标记 + 说明 + 排序。
     * <p>
     * 红线：已发布链路须先下线（模板恒为草稿，不推 Rule-DB、不进执行入口）；
     * 不触碰画布/组件树/EL/状态。
     *
     * @param id 链路主键
     * @param bo 模板设置（说明必填、排序可空）
     * @return 是否设置成功
     */
    Boolean markAsTemplate(Long id, TemplateMarkBo bo);

    /**
     * 取消精选模板：清除标记/说明/排序，链路回到普通草稿（不改变 status）。
     *
     * @param id 链路主键
     * @return 是否取消成功
     */
    Boolean unmarkTemplate(Long id);

    /**
     * 收集链路安装闭包（decisions §十四-1 链路安装全 fork）：以 rootChainCode 为根沿组件树
     * CHAIN 引用递归下钻，返回「主链＋全部传递子链」（纯读不落库）。
     * <p>
     * entry 顺序为 post-order（被引用者先于引用者），即安装复制的施工顺序——子链先就位，
     * 主链引用重定向时新编码已存在。环引用与四类非法子链（不存在/模板/未发布/未编排）的
     * 拒绝口径与发布侧校验一致（decisions §十四-4：安装闭包与发布校验双口径一致降险）。
     *
     * @param rootChainCode 主链编码
     * @return 闭包（key=chainCode，含主链自身，entry 顺序即复制顺序）
     */
    LinkedHashMap<String, DatabusChain> collectChainClosure(String rootChainCode);

    /**
     * 收集单条链路 cmp_property 树里的直接 CHAIN 引用编码（不递归下钻，去重保序）。
     * 审核详情依赖树按引用层级先序展开时用来取每层的孩子（2026-10-24 闭包联审）。
     *
     * @param root 链路结构树根
     * @return 直接引用的 chainCode 清单
     */
    List<String> collectChainRefs(CmpProperty root);

}
