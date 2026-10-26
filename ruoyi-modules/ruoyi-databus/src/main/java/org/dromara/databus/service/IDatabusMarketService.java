package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.MarketInstallMoveBo;
import org.dromara.databus.domain.bo.MarketPublishBatchBo;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.domain.bo.MarketQueryBo;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.domain.vo.DatabusMarketCategoryVo;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.domain.vo.DatabusMarketItemVo;
import org.dromara.databus.domain.vo.DatabusMarketPreviewVo;
import org.dromara.databus.domain.vo.DatabusMarketReviewDetailVo;

import java.util.List;

/**
 * 物料市场 Service 接口（组件 / 链路市场）。
 * <p>
 * 三组能力：浏览（只读列表/分类/详情）、消费（安装＝fork / 卸载 / 我的已装）、
 * 供给（上架待审 / 下架 / 审核 / 删原件）。见设计正本 databus-material-market.md §十一。
 *
 * @author databus
 */
public interface IDatabusMarketService {

    /**
     * 市场物料列表（仅在线；类型/分类/关键字过滤 + 分页；回填当前用户是否已装）
     */
    PageResult<DatabusMarketItemVo> queryPageList(MarketQueryBo bo, PageQuery pageQuery, Long userId);

    /**
     * 分类列表（多级平表，含在线物料计数）
     */
    List<DatabusMarketCategoryVo> queryCategories();

    /**
     * 物料详情（在线或本人可见；回填是否已装）
     */
    DatabusMarketItemVo queryById(Long id, Long userId);

    /**
     * 安装：链路＝复制依赖清单快照进我目录并写安装记录；组件＝共享注册；连接器＝启用
     *
     * @param itemId     市场物料 id
     * @param directoryId 目标目录（null=未归组）
     * @param userId     当前用户 id
     * @return 安装记录视图
     */
    DatabusMarketInstallVo install(Long itemId, Long directoryId, Long userId);

    /**
     * 卸载（删我的本地副本 + 删安装记录；链路被其他链引用时拒删）
     */
    Boolean uninstall(Long resourceId, Long userId);

    /**
     * 我的已装列表（管理页数据源；源已删按本地副本兜底）
     */
    List<DatabusMarketInstallVo> queryInstalled(Long userId);

    /**
     * 引用叶子目录归属移动（引用挂树，§十二-7）：只更新 install 行 directory_id，不动源组件
     *
     * @param bo     安装记录id + 目标目录（null=未归组）
     * @param userId 当前用户 id（owner 校验）
     */
    Boolean moveInstall(MarketInstallMoveBo bo, Long userId);

    /**
     * 提交上架（进待审；同源重复提交则更新并回到待审）
     */
    Boolean publish(MarketPublishBo bo, Long userId, String ownerCode);

    /**
     * 批量提审（POST /databus/market/publish/batch，decisions §十四-6 发布引导弹窗确认后的
     * 落库动作）：循环复用单条 publish 的归属校验/同源更新逻辑，整批 all-or-nothing。
     *
     * @param bo        上架清单（主链发布响应 guideItems 原样回传）
     * @param userId    当前用户 id
     * @param ownerCode 当前用户账号
     */
    Boolean publishBatch(MarketPublishBatchBo bo, Long userId, String ownerCode);

    /**
     * 下架（在线 → 下线；仅本人）
     */
    Boolean unpublish(Long id, Long userId);

    /**
     * 审核（通过→上线 / 驳回→驳回态；禁自审，写审核人与时间）
     *
     * @param id         市场物料 id
     * @param bo         审核意见
     * @param reviewerId 审核人（治理者）id
     */
    Boolean review(Long id, MarketReviewBo bo, Long reviewerId);

    /**
     * 删市场原件（作者主权；与卸载分离）
     */
    Boolean deleteItem(Long id, Long userId);

    /**
     * 我的上架列表（作者视角治理视图，含各状态）
     */
    List<DatabusMarketItemVo> queryMine(Long userId);

    /**
     * 待审核列表（治理者视角，仅 PENDING；全量返回含自己上架的项，自己上架的项由前端
     * 按 owner 隐藏操作按钮——禁自审 decisions §七-7.1，2026-10-24 用户拍板「列表要有，
     * 只是不给自审项操作权限」）。
     *
     * @param userId 当前治理者（前端判断自审项用，列表本身不过滤）
     */
    List<DatabusMarketItemVo> queryPending(Long userId);

    /**
     * 审核详情（GET /databus/market/items/{id}/review-detail，decisions §十四-7 嵌套审核）：
     * 主体 item＋依赖清单状态树（链＋组件），终审前供审核者查看依赖全貌。
     * 仅 CHAIN 物料有依赖树，组件/连接器为空列表。
     *
     * @param id 市场物料 id
     */
    DatabusMarketReviewDetailVo reviewDetail(Long id);

    /**
     * 实现预览（GET /databus/market/items/{id}/preview，2026-10-24 装前可看）：
     * 组件给脚本与契约、链路给节点清单；仅 {@code ONLINE} 物料开放，
     * 非在线一律拒绝，防止审核中/已下架的草稿实现外泄。
     *
     * @param id 市场物料 id
     */
    DatabusMarketPreviewVo preview(Long id);

}