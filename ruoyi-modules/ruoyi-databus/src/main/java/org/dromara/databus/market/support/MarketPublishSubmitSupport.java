package org.dromara.databus.market.support;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.enums.OwnerTypeEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.model.MarketClosure;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Objects;

import static org.dromara.databus.constant.ConnectorConstants.SOURCE_DEV;
import static org.dromara.databus.constant.MarketConstants.CODE_SEPARATOR;
import static org.dromara.databus.constant.MarketConstants.DISPLAY_SEPARATOR;
import static org.dromara.databus.constant.MarketConstants.STATUS_PENDING;
import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;
import static org.dromara.databus.constant.MarketConstants.TYPE_COMPONENT;
import static org.dromara.databus.constant.MarketConstants.TYPE_CONNECTOR;

/**
 * 提交上架：解析源资源 → 校验上架权限 → 新建或复用同源待审行 → 顺带补依赖审核单。
 * <p>
 * 事务边界留在 {@link MarketPublishService#publish}，本类只承接实现。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class MarketPublishSubmitSupport {

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * 查依赖清单：顺带补审核单与审核详情依赖树共用同一份清单
     */
    private final MarketClosureLoader closureLoader;

    /**
     * manifest 快照解析用的 Jackson3 mapper（构造注入容器里的 bean：平台是 Spring Boot4/Jackson3，
     * 容器提供的是 tools.jackson 的 ObjectMapper/JsonMapper，带着全局的序列化设置）
     */
    private final ObjectMapper objectMapper;

    /**
     * 提交上架：新建一条待审物料行；如果这个资源以前上架过，就把原来那行的展示信息改掉、
     * 状态回到待审。提交链路时还会顺带给同一个人的依赖补审核单。
     *
     * @return 物料行 id（新建或复用的同源行），供调用方定位「刚提交的是哪一条」
     */
    public Long publish(MarketPublishBo bo, Long userId, String ownerCode) {
        String resourceType = bo.getResourceType();
        // 连接器包上传上架时 sourceId 由 publishConnector 回填；走到这里还为空说明调用方没给源资源
        if (bo.getSourceId() == null) {
            throw new ServiceException("源资源id不能为空");
        }
        String sourceCode;
        String name;
        String icon = null;
        String color = null;
        Long sourceCreateBy;
        // 归属类型（SYSTEM/USER）：上架权限判据（系统/用户归属由显式字段决定，不借用审计字段 create_by）
        String sourceOwnerType;
        // 主链的源行（只有 CHAIN 才不为空）：方法末尾要按它查依赖清单补审核单，先存下来
        DatabusChain chainSource = null;
        if (TYPE_CHAIN.equals(resourceType)) {
            DatabusChain source = chainMapper.selectById(bo.getSourceId());
            if (source == null) {
                throw new ServiceException("源链路不存在或已删除");
            }
            sourceCode = source.getChainCode();
            name = source.getChainName();
            sourceCreateBy = source.getCreateBy();
            sourceOwnerType = source.getOwnerType();
            chainSource = source;
        } else if (TYPE_COMPONENT.equals(resourceType)) {
            DatabusComponent source = componentMapper.selectById(bo.getSourceId());
            if (source == null) {
                throw new ServiceException("源组件不存在或已删除");
            }
            sourceCode = source.getComponentCode();
            name = source.getComponentName();
            icon = source.getIcon();
            color = source.getColor();
            sourceCreateBy = source.getCreateBy();
            sourceOwnerType = source.getOwnerType();
        } else if (TYPE_CONNECTOR.equals(resourceType)) {
            // 连接器（§15.7）：sourceId 就是 databus_connector_def 的行 id；编码用它全局唯一的 type，
            // 名字和图标从 def 行存的 manifest 快照里取（上传的时候已经校验过不为空）
            DatabusConnectorDef source = connectorDefMapper.selectById(bo.getSourceId());
            if (source == null) {
                throw new ServiceException("源连接器不存在或已卸载");
            }
            // dev 目录装载的连接器不能上架：它跑的是编译输出目录，package_path 下没有对应的 zip，
            // 审核人没有可核对的对象（下载原包 / 包内清单这两个入口对它也是拒的）
            if (SOURCE_DEV.equals(source.getSource())) {
                throw new ServiceException("连接器 " + source.getType()
                    + " 是 dev 目录装载的，不能提交上架（它没有落地的包文件，审核人无从核对）；"
                    + "请先 mvn package，再在「上架物料」里选择连接器并上传该 zip");
            }
            sourceCode = source.getType();
            JsonNode manifest = readManifest(source.getManifestSnapshot());
            name = manifest.path("label").asText(null);
            if (StringUtils.isBlank(name)) {
                name = sourceCode;
            }
            icon = blankToNull(manifest.path("icon").asText(null));
            sourceCreateBy = source.getCreateBy();
            sourceOwnerType = source.getOwnerType();
        } else {
            throw new ServiceException("不支持的物料类型：" + resourceType);
        }
        // 上架权限（§七 7.1）：
        //   ① owner_type=SYSTEM 的系统资源，只有带 databus:market:review 权限的治理角色能上架；
        //   ② 其余资源（owner_type=USER）只有 create_by 指向的作者本人能上架。
        // 系统/用户归属由显式 owner_type 字段决定，不借用审计字段 create_by。
        if (OwnerTypeEnum.SYSTEM.getCode().equals(sourceOwnerType)) {
            if (!StpUtil.hasPermission("databus:market:review")) {
                throw new ServiceException("系统组件仅治理角色可上架");
            }
        } else if (!Objects.equals(sourceCreateBy, userId)) {
            throw new ServiceException("只能上架属于自己的资源");
        }
        if (StringUtils.isBlank(sourceCode)) {
            throw new ServiceException("源资源编码为空，无法上架");
        }
        if (sourceCode.contains(CODE_SEPARATOR)) {
            throw new ServiceException("源编码含系统保留分隔符 \"" + CODE_SEPARATOR + "\"，无法上架");
        }

        String codeDisplay = ownerCode + DISPLAY_SEPARATOR + sourceCode;
        LocalDateTime now = LocalDateTime.now();
        DatabusMarketItem existing = marketItemMapper.selectOne(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getOwnerId, userId)
            .eq(DatabusMarketItem::getResourceType, resourceType)
            .eq(DatabusMarketItem::getSourceId, bo.getSourceId())
            .last("limit 1"));
        Long itemId;
        boolean published;
        if (existing != null) {
            // 同一个资源重复提交：改掉展示信息、状态回到待审，顺便清掉上一次的驳回理由和审核记录
            existing.setName(name);
            existing.setIcon(icon);
            existing.setColor(color);
            existing.setSummary(bo.getSummary());
            existing.setCategoryId(bo.getCategoryId());
            existing.setTags(bo.getTags());
            existing.setModifyNote(bo.getModifyNote());
            existing.setCodeDisplay(codeDisplay);
            existing.setStatus(STATUS_PENDING);
            existing.setRemark("");
            existing.setUpdateTime(now);
            published = marketItemMapper.updateById(existing) > 0;
            itemId = existing.getId();
            // updateById 默认忽略 null 字段，没法把 review_by/review_time 置空 → 单独发一条 update 清空
            marketItemMapper.update(null, Wrappers.<DatabusMarketItem>lambdaUpdate()
                .eq(DatabusMarketItem::getId, existing.getId())
                .set(DatabusMarketItem::getReviewBy, null)
                .set(DatabusMarketItem::getReviewTime, null));
        } else {
            DatabusMarketItem add = new DatabusMarketItem();
            add.setOwnerId(userId);
            add.setOwnerCode(ownerCode);
            add.setResourceType(resourceType);
            add.setSourceId(bo.getSourceId());
            add.setCodeDisplay(codeDisplay);
            add.setName(name);
            add.setSummary(bo.getSummary());
            add.setIcon(icon);
            add.setColor(color);
            add.setCategoryId(bo.getCategoryId());
            add.setTags(bo.getTags());
            add.setStatus(STATUS_PENDING);
            add.setInstallCount(0);
            add.setRefCount(0);
            add.setForkPublishCount(0);
            add.setCreateTime(now);
            add.setUpdateTime(now);
            published = marketItemMapper.insert(add) > 0;
            itemId = add.getId();
        }
        // 顺带补审核单（2026-10-24 定的）：主链的审核单写库后，再按依赖清单补其他审核单
        if (published && chainSource != null) {
            autoPublishClosureDeps(chainSource, userId, ownerCode, now);
        }
        if (!published) {
            throw new ServiceException("上架失败，请重试");
        }
        return itemId;
    }

    /**
     * 顺带补审核单（2026-10-24 定的）：提交一条链路审核时，把它的依赖清单也扫一遍，
     * 凡是同一个人所有、市场里还没有审核单的子链和组件，自动补一条待审（PENDING）审核单。
     * 这样审核人从主链这一条审核单就能把整棵依赖树审完，不用等作者一个一个上架。
     * 这里查的依赖清单和「通过审核前查依赖」「审核详情依赖树」是同一份
     * （collectChainClosure ＋ MarketChainRedirector.collectComponentCodes）。
     * <p>
     * 两种情况跳过不补：① 市场里已经有这个资源的审核单了（不管是谁的、什么状态）——
     * 不重复生成，谁的单子谁自己审；② 不是本人的资源（系统资源 owner_type=SYSTEM，
     * 或 create_by 属于别人）——不替别人上架，这种依赖到审核通过那一步会照常报错并说出具体名字。
     * 自动补的单子 summary/category/tags 留空（作者没填过），作者可以在「我的上架」里补完再重新提交。
     */
    private void autoPublishClosureDeps(DatabusChain root, Long userId, String ownerCode, LocalDateTime now) {
        MarketClosure closure = closureLoader.load(root.getChainCode());
        for (DatabusChain dep : closure.chains().values()) {
            if (Objects.equals(dep.getId(), root.getId()) || !Objects.equals(dep.getCreateBy(), userId)) {
                continue;
            }
            autoPublishDepIfAbsent(TYPE_CHAIN, dep.getId(), dep.getChainCode(), dep.getChainName(),
                null, null, userId, ownerCode, now);
        }
        // 组件这一边：清单里全部链（含主链）引用的组件合在一起去重（和审核通过前的检查用同一份）。
        // 如果某个组件编码在清单里、但组件行已经被删了，这里查不到自然就跳过，
        // 留给审核通过那一步去报错并说出具体名字
        for (DatabusComponent comp : closure.components().values()) {
            if (!Objects.equals(comp.getCreateBy(), userId)) {
                continue;
            }
            autoPublishDepIfAbsent(TYPE_COMPONENT, comp.getId(), comp.getComponentCode(), comp.getComponentName(),
                comp.getIcon(), comp.getColor(), userId, ownerCode, now);
        }
    }

    /** 补一条待审行：只有市场里还没有这个资源的审核单时才插（已有就不重复生成，原状态不动） */
    private void autoPublishDepIfAbsent(String resourceType, Long sourceId, String sourceCode, String name,
                                        String icon, String color, Long userId, String ownerCode, LocalDateTime now) {
        Long exists = marketItemMapper.selectCount(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getResourceType, resourceType)
            .eq(DatabusMarketItem::getSourceId, sourceId));
        if (exists != null && exists > 0) {
            return;
        }
        DatabusMarketItem add = new DatabusMarketItem();
        add.setOwnerId(userId);
        add.setOwnerCode(ownerCode);
        add.setResourceType(resourceType);
        add.setSourceId(sourceId);
        add.setCodeDisplay(ownerCode + DISPLAY_SEPARATOR + sourceCode);
        add.setName(name);
        add.setIcon(icon);
        add.setColor(color);
        add.setStatus(STATUS_PENDING);
        add.setInstallCount(0);
        add.setRefCount(0);
        add.setForkPublishCount(0);
        add.setCreateTime(now);
        add.setUpdateTime(now);
        marketItemMapper.insert(add);
    }

    /**
     * 解析连接器 def 行里存的 manifest 内容（上架时取名字和图标用）。解析不出来直接报错。
     */
    private JsonNode readManifest(String snapshot) {
        try {
            return objectMapper.readTree(snapshot == null ? "" : snapshot);
        } catch (JacksonException e) {
            throw new ServiceException("连接器 manifest 快照解析失败");
        }
    }

    /**
     * 空串或纯空格统一转成 null。
     */
    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
    }

}
