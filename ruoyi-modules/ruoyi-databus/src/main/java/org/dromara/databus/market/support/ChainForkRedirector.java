package org.dromara.databus.market.support;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 装链路时改引用的工具（decisions §十四-3）。
 * <p>
 * 安装会把依赖清单里的每条链都复制一份。复制出来的副本必须指向「这一批一起复制出来的子链副本」，
 * 不能还指向原来的源链——不然安装者删了或改了副本，还会影响到别人。
 * 本工具负责改两个地方的引用：
 * <ul>
 *   <li><b>cmpProperty 组件树</b>（{@link #redirectChainRefs}）：把源树完整复制一份，
 *       再改掉里面 CHAIN 节点的引用。怎么算 CHAIN 节点，和 DatabusChainServiceImpl#collectChainRefs
 *       的规则一样（type=CHAIN 不分大小写；引用的编码取顶层 id，老数据没有就取 properties.id）。
 *       只改 CHAIN 节点。普通节点的 id 和链编码是两套东西、可能重名，一律不动，避免改错。</li>
 *   <li><b>canvasData 画布 JSON</b>（{@link #redirectCanvasData}）：VueFlow 里
 *       data.defType=CHAIN 的节点，把 data.cmpId（子链的 chainCode）换成副本的编码，
 *       name、label 这些展示字段不动，这样前端双击进去打开的是副本那条链。</li>
 * </ul>
 * <p>
 * 这里还提供收集组件树里业务组件编码的方法（{@link #collectComponentCodes}）：
 * 上架引导（decisions §十四-6）和通过审核前查依赖（decisions §十四-7）都用它。
 * 放在本工具里是为了和 DatabusChainServiceImpl#collectChainRefs 挨着，
 * 让「收集链引用」和「收集组件引用」两处的走法保持一致。
 * 副本的 elExpression 不在这里处理：由调用方统一用
 * {@code expressGenerator.generateEL(改过引用的树)} 重新生成（和 copy() 的做法一样）。
 * <p>
 * 全是静态方法、不依赖 Spring，方便单独写测试（和 JsonCodec 自己建 JsonMapper 的做法一样）。
 *
 * @author databus
 * @since 2026/10/23
 */
public final class ChainForkRedirector {

    /**
     * CHAIN 引用节点的类型值，怎么判断和 collectChainRefs 一样（不分大小写）。
     */
    private static final String CHAIN_TYPE = "CHAIN";

    /**
     * 画布里判断「这是 CHAIN 节点」用的字段（前端 useChainDocument/ChainCanvasPane 里的 data.defType）。
     */
    private static final String DEF_TYPE_FIELD = "defType";

    /**
     * EL 关键字的集合（把 ExpressParserEnum.type 都收进来，存成大写，比对时也转大写）：
     * 对上了就说明这个节点是表达式的结构节点（THEN/IF/SWITCH/…，还有 CHAIN 这种占位），
     * 不是业务组件。
     * 直接从枚举的 values() 生成，以后枚举加了新值这里自动跟上，不用手抄一份、也不会抄漏。
     * 注意 ChainConstant.CHAIN 的原值是小写 "chain"（LiteFlow 的常量大小写不统一），
     * 生成集合时统一转成大写，不然 CHAIN 节点会被误认成业务组件、被收进组件清单里。
     */
    private static final Set<String> EL_KEYWORDS = Arrays.stream(ExpressParserEnum.values())
        .map(ExpressParserEnum::getType)
        .map(t -> t.toUpperCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());

    /**
     * 自己建的 JsonMapper：读写画布不需要任何特殊配置（和 context 包 JsonCodec 的做法一样）。
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ChainForkRedirector() {
    }

    /**
     * 把组件树完整复制一份，并改掉里面 CHAIN 节点的引用（decisions §十四-3）。
     * <p>
     * 传进来的源树一点不动；返回的副本和源树之间没有任何对象是共用的
     * （Properties 里的字段都是不可变值，所以只要逐字段复制一份、再新建 List 外壳，两边就完全分开了）。
     * 映射为空说明没什么要改的，直接把传进来的树返回。
     *
     * @param tree        源组件树（就是 databus_chain.cmp_property 里存的那份结构），可以为 null
     * @param codeMapping 「原编码 → 副本编码」的对应表，空的话说明不用改
     * @return 改好的副本树；tree 为 null 时返回 null
     */
    public static CmpProperty redirectChainRefs(CmpProperty tree, Map<String, String> codeMapping) {
        if (tree == null || codeMapping == null || codeMapping.isEmpty()) {
            return tree;
        }
        return copyAndRedirect(tree, codeMapping);
    }

    /**
     * 一层层往下复制＋改写：每个节点都 new 一个新的，
     * CHAIN 节点的顶层 id 和 properties.id 各自单独查一遍对应表来替换
     * （老数据把编码放在 properties.id 里，这种也得改，
     * 不然副本重新生成的 EL 和树对不上）。
     */
    private static CmpProperty copyAndRedirect(CmpProperty source, Map<String, String> codeMapping) {
        CmpProperty copy = new CmpProperty();
        boolean chainRef = source.getType() != null && CHAIN_TYPE.equalsIgnoreCase(source.getType());
        copy.setId(remapIf(source.getId(), chainRef, codeMapping));
        copy.setType(source.getType());
        if (source.getProperties() != null) {
            Properties p = source.getProperties();
            Properties pCopy = new Properties();
            pCopy.setId(remapIf(p.getId(), chainRef, codeMapping));
            pCopy.setTag(p.getTag());
            pCopy.setData(p.getData());
            pCopy.setTitle(p.getTitle());
            if (p.getOutletLabels() != null) {
                pCopy.setOutletLabels(new ArrayList<>(p.getOutletLabels()));
            }
            copy.setProperties(pCopy);
        }
        if (source.getCondition() != null) {
            copy.setCondition(copyAndRedirect(source.getCondition(), codeMapping));
        }
        if (source.getChildren() != null) {
            List<CmpProperty> children = new ArrayList<>(source.getChildren().size());
            for (CmpProperty child : source.getChildren()) {
                children.add(copyAndRedirect(child, codeMapping));
            }
            copy.setChildren(children);
        }
        return copy;
    }

    /**
     * 只有 CHAIN 节点才改引用：普通节点的 id 和链编码是两套东西、可能重名，
     * 就算在对应表里查到了也不动。
     */
    private static String remapIf(String code, boolean chainRef, Map<String, String> codeMapping) {
        if (code == null || !chainRef) {
            return code;
        }
        return codeMapping.getOrDefault(code, code);
    }

    /**
     * 改掉画布 JSON 里 CHAIN 节点引用的子链编码（decisions §十四-3）。
     * <p>
     * 只看 nodes[] 里 data.defType=CHAIN（不分大小写）的节点，
     * 它的 data.cmpId 在对应表里查得到就换成副本编码；
     * 其它字段（name、label、edges、业务组件节点）一律不动。
     * 一个都没查到时，把原来那个字符串直接返回——
     * 不重新转一遍 JSON，免得把库里已有的画布文本改得跟原来不一样。
     *
     * @param canvasData  画布 JSON 字符串（{nodes:[...], edges:[...]}），可以为 null 或空白
     * @param codeMapping 「原编码 → 副本编码」的对应表
     * @return 改好的画布 JSON；不用改、或者结构不对时返回原来的字符串
     * @throws ServiceException JSON 解析或写回失败（和 MarketPublishService#readManifest 一样，直接报错不吞掉）
     */
    public static String redirectCanvasData(String canvasData, Map<String, String> codeMapping) {
        if (canvasData == null || canvasData.isBlank() || codeMapping == null || codeMapping.isEmpty()) {
            return canvasData;
        }
        try {
            JsonNode root = MAPPER.readTree(canvasData);
            JsonNode nodes = root.path("nodes");
            if (!nodes.isArray()) {
                return canvasData;
            }
            boolean changed = false;
            for (JsonNode node : nodes) {
                JsonNode data = node.path("data");
                if (!data.isObject()) {
                    continue;
                }
                String defType = data.path(DEF_TYPE_FIELD).asText(null);
                if (defType == null || !CHAIN_TYPE.equalsIgnoreCase(defType)) {
                    continue;
                }
                String cmpId = data.path("cmpId").asText(null);
                if (cmpId != null && codeMapping.containsKey(cmpId)) {
                    ((ObjectNode) data).put("cmpId", codeMapping.get(cmpId));
                    changed = true;
                }
            }
            return changed ? MAPPER.writeValueAsString(root) : canvasData;
        } catch (JacksonException e) {
            throw new ServiceException("画布数据引用重定向失败: " + e.getOriginalMessage());
        }
    }

    /**
     * 把组件树里用到的业务组件编码都收集出来（decisions §十四-6 上架引导、§十四-7 通过审核前查依赖，
     * 两处都用这一个方法）。
     * <p>
     * 怎么算业务组件节点，和「收集链引用」的规则正好相反：
     * type 不为空、而且不属于 EL 关键字集合（ExpressParserEnum 里的 THEN/WHEN/…/CHAIN，不分大小写），
     * 就是业务组件节点；编码取顶层 id（buildChildrenChain 里写的就是 .id(组件编码)），
     * 老数据没有顶层 id 就取 properties.id。
     * 遍历时 condition 分支和所有 children 都要走一遍，按出现的顺序去重（和 collectChainRefs 做法一样）。
     * 上架引导和通过审核前查依赖都以本方法为准，两处拿到的「依赖组件清单」才能一模一样。
     *
     * @param tree 组件树（databus_chain.cmp_property），可以为 null
     * @return 组件编码列表，一个业务组件都没有时返回空列表
     */
    public static List<String> collectComponentCodes(CmpProperty tree) {
        List<String> codes = new ArrayList<>();
        collectComponentCodes(tree, codes);
        return codes;
    }

    /**
     * 一层层往下收集：EL 关键字那种结构节点只往下走、不收集；
     * type 为 null 的节点也一样只往下走（库里可能有脏数据，
     * 这种认不出是什么，就不当成业务组件）。
     */
    private static void collectComponentCodes(CmpProperty node, List<String> codes) {
        if (node == null) {
            return;
        }
        boolean structure = node.getType() == null
            || EL_KEYWORDS.contains(node.getType().toUpperCase(Locale.ROOT));
        if (!structure) {
            String code = StringUtils.isNotBlank(node.getId()) ? node.getId()
                : (node.getProperties() != null ? node.getProperties().getId() : null);
            if (StringUtils.isNotBlank(code) && !codes.contains(code)) {
                codes.add(code);
            }
        }
        collectComponentCodes(node.getCondition(), codes);
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                collectComponentCodes(child, codes);
            }
        }
    }
}
