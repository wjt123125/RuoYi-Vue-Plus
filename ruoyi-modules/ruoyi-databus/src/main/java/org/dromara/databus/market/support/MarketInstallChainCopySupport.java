package org.dromara.databus.market.support;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.enums.ComponentStatusEnum;
import org.dromara.databus.enums.OwnerTypeEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.market.model.MarketClosure;
import org.dromara.databus.market.model.InstallOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.dromara.databus.constant.ChainConstants.CHAIN_CODE_MAX_LEN;
import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_NO;
import static org.dromara.databus.constant.MarketConstants.CASCADE_NEW;
import static org.dromara.databus.constant.MarketConstants.CASCADE_REUSED;
import static org.dromara.databus.constant.MarketConstants.DRAFT_VERSION;
import static org.dromara.databus.constant.MarketConstants.MODE_FORK;
import static org.dromara.databus.constant.MarketConstants.MODE_SHARED;
import static org.dromara.databus.constant.MarketConstants.STATUS_ONLINE;
import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;
import static org.dromara.databus.constant.MarketConstants.TYPE_COMPONENT;

/**
 * 安装时的物料复制与共享：链路＝把主链及其依赖闭包逐条复制成本地副本（含编码/EL/画布重定向），
 * 组件＝直接复用源组件不复制；安装前的闭包市场状态校验也在这里。
 * <p>
 * 只被 MarketInstallService 调用，不反向依赖它；编码生成与内容哈希委托给
 * {@link MarketInstallCodeSupport}。
 */
@RequiredArgsConstructor
@Component
public class MarketInstallChainCopySupport {

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    /**
     * EL 生成器：副本的 elExpression 一律拿重定向之后的组件树重新生成一遍
     * （decisions §十四-3，跟 copy() 的做法一样——项目里没有手写 EL 这种模式，
     * 生成器产出的 EL 和原来的意思是一样的）
     */
    private final ExpressGenerator expressGenerator;

    /**
     * 查依赖清单：安装前用它检查这些依赖在市场里的状态
     */
    private final MarketClosureLoader closureLoader;

    private final MarketInstallCodeSupport codeSupport;

    /**
     * 装链路＝复制一份副本（decisions §十四-1）：主链和它引用的全部子链（包括子链再引用的）
     * 一条条复制出来，每条都是独立的行，都放在「我的」目录最外层，
     * 编码加 u{ownerId}__ 前缀避免和别人的撞（目录和前缀只有装链路时才用）
     */
    public InstallOutcome forkChainClosure(DatabusMarketItem item, Long directoryId, Long userId) {
        Long targetDirectoryId = codeSupport.normalizeDirectoryId(directoryId);
        String prefix = codeSupport.namespacePrefix(userId);
        DatabusChain source = chainMapper.selectById(item.getSourceId());
        if (source == null) {
            throw new ServiceException("源链路不存在或已删除");
        }
        // 复制前先做两件事：查依赖清单（顺序是从被引用的到引用它的）＋检查市场状态
        // （§十四-4：清单里所有链都必须是 ONLINE。作者改版重新提交审核这段时间就不给装，
        // 这样安装者拿到的永远是整条链都过了审的那一份）
        MarketClosure closure = closureLoader.load(source.getChainCode());
        validateClosureOnMarket(closure);
        // 按「先复制被引用的、再复制引用它的」这个顺序一条条复制：这样改链引用的时候，
        // 子链的新编码已经知道了。靠「原编码→新编码」这张对应表改三个地方
        // （cmpProperty 里的链节点、canvasData 里的 data.cmpId、EL 重新生成）
        Map<String, String> codeMapping = new HashMap<>();
        List<DatabusMarketInstallVo.CascadeItemVo> cascade = new ArrayList<>(closure.chains().size());
        Long mainCopyId = null;
        for (DatabusChain sourceChain : closure.chains().values()) {
            boolean main = Objects.equals(sourceChain.getId(), source.getId());
            DatabusChain existing = codeSupport.findReusableSameSourceCopy(userId, sourceChain);
            String newCode;
            String cascadeStatus;
            if (existing != null) {
                // 能复用就不重复复制（decisions §十四-2、§十五-6）：我名下已经有一条
                // 从同一条源链复制来的、装完之后一直没动过的副本（重复点安装、
                // 或者这个副本在装别的物料时已经复制过了）→ 直接用那一行，不再复制
                newCode = existing.getChainCode();
                cascadeStatus = CASCADE_REUSED;
                if (main) {
                    mainCopyId = existing.getId();
                }
            } else {
                // 没有能复用的：第一次装，或者已有的副本被改过、被标成了精选模板，
                // 或者老数据的 source_chain_id 是 NULL 认不出是不是同一条源链
                // → 从市场原版复制一份，编码加后缀避开已有的副本
                newCode = codeSupport.buildUniqueCode(prefix, sourceChain.getChainCode(), CHAIN_CODE_MAX_LEN,
                    codeSupport::existsChainCode);
                cascadeStatus = CASCADE_NEW;
                DatabusChain copy = copyChain(sourceChain, newCode, targetDirectoryId, codeMapping);
                chainMapper.insert(copy);
                if (main) {
                    mainCopyId = copy.getId();
                }
            }
            codeMapping.put(sourceChain.getChainCode(), newCode);
            cascade.add(cascadeItem(sourceChain, newCode, cascadeStatus));
        }
        // 主链一定在清单的最后一个（collectChainClosure 就是这么排的），所以 mainCopyId 一定有值
        return new InstallOutcome(mainCopyId, TYPE_CHAIN, MODE_FORK, codeSupport.chainHash(source), null, null, null,
            cascade);
    }

    /**
     * 复制一条链（decisions §十四-3）：把它引用的链编码改成副本的新编码，
     * 副本不会指回原来的链。三个字段一起写——cmpProperty 改成指向副本的树、
     * canvasData 里链节点的 data.cmpId 同步改、EL 一律交给生成器重新产出
     * （和 copy() 一样，组件树为空就不产 EL）——项目里没有手写 EL 这种模式，
     * 生成出来的 EL 和原来意思一样
     */
    private DatabusChain copyChain(DatabusChain sourceChain, String newCode, Long targetDirectoryId,
                                   Map<String, String> codeMapping) {
        CmpProperty redirectedTree = MarketChainRedirector.redirectChainRefs(sourceChain.getCmpProperty(), codeMapping);
        DatabusChain copy = new DatabusChain();
        copy.setChainCode(newCode);
        copy.setChainName(sourceChain.getChainName());
        copy.setVersion(DRAFT_VERSION);
        copy.setStatus(ChainStatusEnum.DRAFT.getCode());
        copy.setLogLevel(sourceChain.getLogLevel());
        copy.setInputParams(sourceChain.getInputParams());
        copy.setDirectoryId(targetDirectoryId);
        copy.setIsTemplate(TEMPLATE_FLAG_NO);
        copy.setOwnerType(OwnerTypeEnum.USER.getCode());
        copy.setRemark(sourceChain.getRemark());
        // 记下是从哪条源链复制来的（decisions §十四-2/5）：主链副本和跟着复制的子链副本
        // 都填 source_chain_id（靠编码认不出来的老数据就靠这个字段认）
        copy.setSourceChainId(sourceChain.getId());
        copy.setCmpProperty(redirectedTree);
        copy.setCanvasData(MarketChainRedirector.redirectCanvasData(sourceChain.getCanvasData(), codeMapping));
        copy.setElExpression(redirectedTree == null ? null : expressGenerator.generateEL(redirectedTree).getElStr());
        return copy;
    }

    /**
     * 复制清单里的一项：源编码→新编码，以及这一条是 NEW（新复制的）还是 REUSED（复用了已有副本）
     */
    private DatabusMarketInstallVo.CascadeItemVo cascadeItem(DatabusChain sourceChain, String newCode, String status) {
        DatabusMarketInstallVo.CascadeItemVo cascadeItem = new DatabusMarketInstallVo.CascadeItemVo();
        cascadeItem.setSourceCode(sourceChain.getChainCode());
        cascadeItem.setNewCode(newCode);
        cascadeItem.setSourceName(sourceChain.getChainName());
        cascadeItem.setStatus(status);
        return cascadeItem;
    }

    /**
     * 装组件＝大家一起用同一个（§十二-5）：不复制副本，localResourceId 直接指向源组件——
     * 这样运行时占的内存只跟组件总数有关，跟多少人装过没关系；作者更新、停用、下架都马上生效，
     * 卸载只删安装记录，组件本体不动。
     * 目录归属（§十二-7）：装在哪个目录记在安装记录行上，和源组件行自己的 directory_id 互不影响
     */
    public InstallOutcome shareComponent(DatabusMarketItem item, Long directoryId) {
        DatabusComponent source = componentMapper.selectById(item.getSourceId());
        if (source == null) {
            throw new ServiceException("源组件不存在或已删除");
        }
        return new InstallOutcome(source.getId(), TYPE_COMPONENT, MODE_SHARED, codeSupport.componentHash(source),
            codeSupport.normalizeDirectoryId(directoryId), source.getComponentCode(),
            ComponentStatusEnum.ENABLED.getCode().equals(source.getStatus()), null);
    }

    /**
     * 安装前的市场检查（decisions §十四-4）：依赖清单里每条链（包括主链）都必须在市场里存在、
     * 而且状态是 ONLINE。
     * <p>
     * 装链路能不能装，就看市场状态——作者改版重新发布时，ONLINE 的物料会被改回 PENDING
     * （decisions §十四-8），这段时间就不给装，所以安装者拿到的永远是过了审的那一份。
     * 不管是查不到还是状态不是 ONLINE（PENDING / REJECTED / OFFLINE），报错都直接说出是哪条链，
     * 安装者不用自己去翻清单里都有什么。
     * <p>
     * 只查链、不查组件：组件是大家共用同一个，运行时直接指向源组件，
     * 源组件被停用了由运行时处理，不在安装这一步拦。
     *
     * @param closure 已经查好的依赖清单（包括主链，链的遍历顺序就是复制顺序）
     */
    public void validateClosureOnMarket(MarketClosure closure) {
        for (DatabusChain chain : closure.chains().values()) {
            DatabusMarketItem item = closure.chainItems().get(chain.getId());
            if (item == null || !STATUS_ONLINE.equals(item.getStatus())) {
                String state = item == null ? "未上架" : item.getStatus();
                throw new ServiceException("依赖链路'" + chain.getChainName() + "'市场状态为" + state + "，暂不可安装");
            }
        }
    }
}
