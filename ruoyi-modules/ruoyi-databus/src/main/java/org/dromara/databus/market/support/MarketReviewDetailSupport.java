package org.dromara.databus.market.support;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.vo.DatabusMarketReviewDetailVo;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.model.MarketClosure;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.dromara.databus.constant.MarketConstants.STATUS_ONLINE;
import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;
import static org.dromara.databus.constant.MarketConstants.TYPE_COMPONENT;
import static org.dromara.databus.constant.MarketConstants.TYPE_CONNECTOR;

/**
 * 审核详情装配（只读）：物料预览、依赖树（按引用关系逐层展开）、每条链的 blockedBy。
 * <p>
 * 与「通过审核前查依赖」共用 {@link MarketClosureLoader} 的同一份清单，
 * 所以审核人在这里看到的状态，就是点通过时会核对的状态。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class MarketReviewDetailSupport {

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * 链路服务：展开依赖树时，用它取子链引用了哪些链
     */
    private final IDatabusChainService chainService;

    private final MarketClosureLoader closureLoader;

    private final MarketVoAssembler assembler;

    /**
     * 审核详情：物料本身 ＋ 它依赖了哪些东西及各自的市场状态 ＋ 内容预览。
     */
    public DatabusMarketReviewDetailVo reviewDetail(Long id) {
        // 审核详情（GET /databus/market/items/{id}/review-detail，decisions §十四-7）：
        // 让审核人在点通过之前先看清依赖情况。
        // 查依赖清单失败时（有环、或子链查不到），直接把 ServiceException 抛出去不接——
        // 这样审核人看到的报错，和他点通过时会被拒的报错是同一句，不用来回试。
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        DatabusMarketReviewDetailVo vo = new DatabusMarketReviewDetailVo();
        vo.setItem(assembler.toItemVo(item));
        List<DatabusMarketReviewDetailVo.DependencyVo> dependencies = new ArrayList<>();
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            DatabusChain root = chainMapper.selectById(item.getSourceId());
            // 源链已被删：物料本身照常返回、依赖给空列表，这里不报错。
            // 真正的拒绝发生在点「通过」的时候。
            if (root != null) {
                dependencies.addAll(buildReviewDependencies(root));
            }
        }
        vo.setDependencies(dependencies);
        // 内容预览（decisions §十四-7）：链路给画布节点清单，组件给关键信息
        vo.setPreview(buildReviewPreview(item));
        return vo;
    }

    /**
     * 拼审核用的内容预览（decisions §十四-7）：让审核人看到物料里面大概是什么，
     * 不用只看个标题就通过。
     * <p>
     * 链路：把源链的画布配置树走一遍，控制结构（ExpressParserEnum 里的关键字）和
     * 业务组件并列列出，只展示整条链的骨架，不展开脚本和 EL 的细节。
     * 组件那一行会去查组件名，组件已被删就直接显示编码。
     * 组件：作者（上架展示码）、版本、脚本语言、描述。
     */
    private DatabusMarketReviewDetailVo.PreviewVo buildReviewPreview(DatabusMarketItem item) {
        DatabusMarketReviewDetailVo.PreviewVo preview = new DatabusMarketReviewDetailVo.PreviewVo();
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            DatabusChain chain = chainMapper.selectById(item.getSourceId());
            if (chain == null || chain.getCmpProperty() == null) {
                return preview; // 源链已被删：给个空预览，点通过时会被拒
            }
            List<DatabusMarketReviewDetailVo.NodeVo> nodes = new ArrayList<>();
            assembler.collectPreviewNodes(chain.getCmpProperty(), nodes, 0);
            preview.setNodes(nodes);
        } else if (TYPE_COMPONENT.equals(item.getResourceType())) {
            DatabusComponent component = componentMapper.selectById(item.getSourceId());
            if (component == null) {
                return preview;
            }
            // 作者这里填上架人的展示码，不填组件的 create_by：审核人想知道的是「谁上架的」，
            // 而系统资源类组件的 create_by 是空的，跨模块去查用户也没必要。
            preview.setAuthor(item.getOwnerCode());
            preview.setCode(component.getComponentCode());
            preview.setVersion(component.getVersion() == null ? null : String.valueOf(component.getVersion()));
            preview.setScriptLang(component.getScriptLang());
            preview.setDescription(component.getDescription());
        } else if (TYPE_CONNECTOR.equals(item.getResourceType())) {
            // 连接器（P1 看得见）：预览给下载/清单端点所需的 type + version；code 复用为连接器类型键
            DatabusConnectorDef def = connectorDefMapper.selectById(item.getSourceId());
            if (def == null) {
                return preview;
            }
            preview.setAuthor(item.getOwnerCode());
            preview.setCode(def.getType());
            preview.setVersion(def.getVersion());
            preview.setBuildNo(def.getBuildNo());
            preview.setConnectorStatus(def.getStatus());
        }
        return preview;
    }

    /**
     * 拼审核详情的依赖列表（decisions §十四-7）：把依赖清单里的链（正在审的这条主链不重复列）
     * 和所有链引用的业务组件都列出来，每一项都去查它在市场里的状态。
     * 这份清单和「通过审核前查依赖」「安装时查依赖」用的是同一份，
     * 所以审核人在这里看到的状态，就是点通过时程序会核对的那些。
     * <p>
     * 2026-10-24 定的排列方式：不再「先列所有链、再列所有组件」，改成按引用关系一层层展开——
     * 一条链引用的组件紧跟在这条链下面（depth+1），它引用的子链再往下展开。
     * 被多处共用的依赖只在第一次出现的地方列一次，状态是全局的，不用重复审。
     * 每条链依赖还带一个 blockedBy：把它自己依赖的东西里市场状态还不是 ONLINE 的名字列出来
     * （被挪了位置的共用依赖也算进去，判断标准和点通过时一致）——
     * 审核人照着这个提示从最底层的先审，不用靠程序报错来试。
     *
     * @param root 正在审的这条链路的源链（它自己是审核对象，不进列表，所以不用再传 id 去排除）
     */
    private List<DatabusMarketReviewDetailVo.DependencyVo> buildReviewDependencies(DatabusChain root) {
        MarketClosure closure = closureLoader.load(root.getChainCode());
        List<DatabusMarketReviewDetailVo.DependencyVo> dependencies = new ArrayList<>();
        expandReviewDeps(root, 0, closure, new HashSet<>(), new HashSet<>(), dependencies);
        // blockedBy：这条链自己依赖的东西里，市场状态还不是 ONLINE 的那些。
        // 上面展示列表做了去重挪位，但这里算的是它真实的依赖，不受挪位影响。
        for (DatabusMarketReviewDetailVo.DependencyVo dep : dependencies) {
            if (!TYPE_CHAIN.equals(dep.getResourceType())) {
                continue;
            }
            DatabusChain sub = closure.chains().get(dep.getCode());
            if (sub != null) {
                dep.setBlockedBy(collectUnonlineDeps(sub, closure, new HashSet<>(), new ArrayList<>()));
            }
        }
        return dependencies;
    }

    /**
     * 按引用关系一层层展开（2026-10-24 定的）：先把当前链引用的组件紧跟列出来（depth+1），
     * 再挨个展开它引用的子链。root 自己是审核对象不进列表，它的直接依赖 depth=1。
     * listedChains / listedComponents 用来去重——共用的依赖只在第一次出现的地方列一次。
     * 环引用在 collectChainClosure 那一步就已经被拒了，这里不用再防。
     */
    private void expandReviewDeps(DatabusChain chain, int depth, MarketClosure closure,
                                  Set<String> listedChains, Set<String> listedComponents,
                                  List<DatabusMarketReviewDetailVo.DependencyVo> out) {
        for (String code : MarketChainRedirector.collectComponentCodes(chain.getCmpProperty())) {
            if (!listedComponents.add(code)) {
                continue;
            }
            // 组件已被删：sourceId 给 null、名字直接用编码。列表照常展示，点通过时会被拒
            DatabusComponent component = closure.components().get(code);
            DatabusMarketReviewDetailVo.DependencyVo vo = component == null
                ? dependency(TYPE_COMPONENT, null, code, code, null)
                : dependency(TYPE_COMPONENT, component.getId(), component.getComponentCode(),
                    component.getComponentName(), closure.componentItems().get(component.getId()));
            vo.setDepth(depth + 1);
            out.add(vo);
        }
        for (String refCode : chainService.collectChainRefs(chain.getCmpProperty())) {
            DatabusChain sub = closure.chains().get(refCode);
            if (sub == null || !listedChains.add(refCode)) {
                continue;
            }
            DatabusMarketReviewDetailVo.DependencyVo vo = dependency(TYPE_CHAIN, sub.getId(), sub.getChainCode(),
                sub.getChainName(), closure.chainItems().get(sub.getId()));
            vo.setDepth(depth + 1);
            out.add(vo);
            expandReviewDeps(sub, depth + 1, closure, listedChains, listedComponents, out);
        }
    }

    /**
     * 查这条链还差哪些依赖没上线（2026-10-24 定的，对应 blockedBy 字段）：
     * 递归走一遍它真实的依赖清单，把市场状态不是 ONLINE 的链和组件名字收进来
     * （没上架的、已被删查不到的都算）。返回的就是「该先审谁」。
     * 组件没有依赖清单，所以组件这一层不再往下走。
     * 环引用在 collectChainClosure 那一步就已经被拒了。
     */
    private List<String> collectUnonlineDeps(DatabusChain chain, MarketClosure closure,
                                             Set<String> visited, List<String> blocked) {
        for (String code : MarketChainRedirector.collectComponentCodes(chain.getCmpProperty())) {
            DatabusComponent component = closure.components().get(code);
            if (component == null) {
                if (!blocked.contains(code)) {
                    blocked.add(code);
                }
                continue;
            }
            DatabusMarketItem it = closure.componentItems().get(component.getId());
            if ((it == null || !STATUS_ONLINE.equals(it.getStatus())) && !blocked.contains(component.getComponentName())) {
                blocked.add(component.getComponentName());
            }
        }
        for (String refCode : chainService.collectChainRefs(chain.getCmpProperty())) {
            if (!visited.add(refCode)) {
                continue;
            }
            DatabusChain sub = closure.chains().get(refCode);
            if (sub == null) {
                blocked.add(refCode);
                continue;
            }
            DatabusMarketItem it = closure.chainItems().get(sub.getId());
            if (it == null || !STATUS_ONLINE.equals(it.getStatus())) {
                blocked.add(sub.getChainName());
            }
            collectUnonlineDeps(sub, closure, visited, blocked);
        }
        return blocked;
    }

    /**
     * 拼一条依赖记录：市场上架行查得到时，把物料 id、状态、审核备注填进去
     * （状态是 REJECTED 时，备注里就是驳回理由）。
     */
    private DatabusMarketReviewDetailVo.DependencyVo dependency(String resourceType, Long sourceId, String code,
                                                                String name, DatabusMarketItem marketItem) {
        DatabusMarketReviewDetailVo.DependencyVo dep = new DatabusMarketReviewDetailVo.DependencyVo();
        dep.setResourceType(resourceType);
        dep.setSourceId(sourceId);
        dep.setCode(code);
        dep.setName(name);
        if (marketItem != null) {
            dep.setMarketItemId(marketItem.getId());
            dep.setOwnerId(marketItem.getOwnerId());
            dep.setMarketStatus(marketItem.getStatus());
            dep.setRemark(marketItem.getRemark());
            // 依赖项如果也是被驳回后重提的，审核人在这里能看到作者写的修改说明
            dep.setModifyNote(marketItem.getModifyNote());
        }
        return dep;
    }

}
