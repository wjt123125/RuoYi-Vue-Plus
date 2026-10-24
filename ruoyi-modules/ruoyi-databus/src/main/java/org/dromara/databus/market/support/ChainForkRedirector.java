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
 * 链路全 fork 安装的引用重定向工具（decisions §十四-3）。
 * <p>
 * 安装复制闭包内每条链后，副本必须指向「同批复制出来的子链副本」而不是源链，
 * 否则安装者删除/改版副本仍会影响他人。本工具负责两处引用改写：
 * <ul>
 *   <li><b>cmpProperty 树</b>（{@link #redirectChainRefs}）：深拷贝源树并改写
 *       CHAIN 叶子引用，判定口径与 DatabusChainServiceImpl#collectChainRefs 对称
 *       （type=CHAIN 大小写不敏感；引用 code 取顶层 id，历史数据兜底 properties.id）。
 *       只改 CHAIN 叶子，普通节点 id 与链 code 同池可重名，一律不动（防误伤）。</li>
 *   <li><b>canvasData 画布 JSON</b>（{@link #redirectCanvasData}）：VueFlow node 的
 *       data.defType=CHAIN 节点，把 data.cmpId（子链 chainCode）替换为副本 code，
 *       name/label 等展示字段不动，保证前端双击下钻落到副本链。</li>
 * </ul>
 * <p>
 * 另附闭包业务组件叶子编码收集（{@link #collectComponentCodes}）：发布侧引导收集
 * （decisions §十四-6）与终审 gate（decisions §十四-7）的共用口径，落在本工具与
 * DatabusChainServiceImpl#collectChainRefs 同族，保证「链引用/组件引用」两套遍历对称。
 * 副本 elExpression 不在此处理：由调用方统一走
 * {@code expressGenerator.generateEL(重定向后的树)} 重生成（copy() 先例口径）。
 * <p>
 * 纯静态工具、零 Spring 依赖，便于单测隔离（对齐 JsonCodec 的自建 JsonMapper 先例）。
 *
 * @author databus
 * @since 2026/10/23
 */
public final class ChainForkRedirector {

    /**
     * CHAIN 引用叶子类型，判定与 collectChainRefs 对称（大小写不敏感）。
     */
    private static final String CHAIN_TYPE = "CHAIN";

    /**
     * 画布 CHAIN 节点判据字段（前端 useChainDocument/ChainCanvasPane 的 data.defType）。
     */
    private static final String DEF_TYPE_FIELD = "defType";

    /**
     * EL 关键字集合（ExpressParserEnum.type 汇总，大写形态存储、比对时 toUpperCase）：
     * 命中即表达式结构节点（THEN/IF/SWITCH/…/CHAIN 占位单元），不是业务组件叶子。
     * 从枚举 values() 流式构建，随枚举增补自动同步，不手抄清单防漂移。
     * 注意 ChainConstant.CHAIN 原值为小写 "chain"（LiteFlow 常量大小写不一），
     * 构建时统一 toUpperCase 归一，否则 CHAIN 叶子误判为业务组件被收进组件清单。
     */
    private static final Set<String> EL_KEYWORDS = Arrays.stream(ExpressParserEnum.values())
        .map(ExpressParserEnum::getType)
        .map(t -> t.toUpperCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());

    /**
     * 自建 JsonMapper：画布读写是无配置基础操作（对齐 context 包 JsonCodec 先例）。
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ChainForkRedirector() {
    }

    /**
     * 深拷贝组件树并改写 CHAIN 引用（decisions §十四-3）。
     * <p>
     * 源树不动；返回的副本与源树无任何共享引用（Properties 字段全为不可变值，
     * 字段级复制＋List 外壳新建即完全隔离）。映射为空时无改写必要，直接返回源引用。
     *
     * @param tree        源组件树（databus_chain.cmp_property 的权威结构），可为 null
     * @param codeMapping 原 chainCode → 副本 chainCode 映射，空映射表示无需重定向
     * @return 改写后的深拷贝树；tree 为 null 时返回 null
     */
    public static CmpProperty redirectChainRefs(CmpProperty tree, Map<String, String> codeMapping) {
        if (tree == null || codeMapping == null || codeMapping.isEmpty()) {
            return tree;
        }
        return copyAndRedirect(tree, codeMapping);
    }

    /**
     * 递归深拷贝＋改写：每个节点独立 new，CHAIN 叶子的顶层 id 与 properties.id
     * 各自独立命中映射替换（兜底路径也要改写，否则副本 EL 与树不一致）。
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
     * 仅 CHAIN 叶子参与重定向：普通节点 id 与链 code 同池可重名，命中也不动。
     */
    private static String remapIf(String code, boolean chainRef, Map<String, String> codeMapping) {
        if (code == null || !chainRef) {
            return code;
        }
        return codeMapping.getOrDefault(code, code);
    }

    /**
     * 改写画布 JSON 中 CHAIN 节点的子链引用（decisions §十四-3）。
     * <p>
     * nodes[] 中 data.defType=CHAIN（大小写不敏感）的节点，data.cmpId 命中映射则替换；
     * 其余字段（name/label/edges/组件节点）原样保留。无命中时原字符串原样返回，
     * 避免无谓的重序列化改变存量画布文本。
     *
     * @param canvasData  画布 JSON 串（{nodes:[...], edges:[...]}），可为 null/空白
     * @param codeMapping 原 chainCode → 副本 chainCode 映射
     * @return 改写后的画布 JSON；无改写必要或结构异常时返回原串
     * @throws ServiceException JSON 解析/序列化失败（对齐 DatabusMarketServiceImpl#readManifest 口径）
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
     * 收集组件树中的业务组件叶子编码（decisions §十四-6 / §十四-7 共用口径）。
     * <p>
     * 叶子判定与 CHAIN 引用收集对称：type 非空且不属于 EL 关键字集合
     * （ExpressParserEnum：THEN/WHEN/…/CHAIN，大小写不敏感）即业务组件节点；
     * 编码取顶层 id（buildChildrenChain 的 .id(组件编码)），历史数据兜底 properties.id。
     * 遍历含 condition 分支与全部 children，按出现顺序去重（与 collectChainRefs 同法）。
     * 发布侧引导收集与终审 gate 都以本方法为准，保证两处「依赖组件清单」逐字一致。
     *
     * @param tree 组件树（databus_chain.cmp_property），可为 null
     * @return 组件编码列表，无组件叶子时为空列表
     */
    public static List<String> collectComponentCodes(CmpProperty tree) {
        List<String> codes = new ArrayList<>();
        collectComponentCodes(tree, codes);
        return codes;
    }

    /**
     * 递归收集：结构节点（EL 关键字）只下钻不收集；type 为 null 的节点同样只下钻
     * （防御存量脏数据，不判成组件叶子）。
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
