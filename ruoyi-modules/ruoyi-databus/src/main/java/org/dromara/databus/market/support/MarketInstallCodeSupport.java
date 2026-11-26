package org.dromara.databus.market.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_YES;
import static org.dromara.databus.constant.MarketConstants.CODE_SEPARATOR;
import static org.dromara.databus.constant.MarketConstants.CODE_SEQ_MAX;
import static org.dromara.databus.constant.MarketConstants.HASH_SEP;

/**
 * 安装侧的编码、复用探测与内容哈希工具：副本编码生成（前缀/截断/去重）、
 * 同源干净副本查找、链路与组件的内容标识值计算，以及几个字符串小工具。
 * <p>
 * 只被 MarketInstallService 及其安装协作者（复制/卸载/查询）调用，不含业务编排，
 * 所有方法都是无状态的纯计算或单表查询。
 */
@RequiredArgsConstructor
@Component
public class MarketInstallCodeSupport {

    private final DatabusChainMapper chainMapper;

    /**
     * 目录统一处理：null 或者不是正数，都当「未归组」（null）。
     */
    public Long normalizeDirectoryId(Long directoryId) {
        return (directoryId == null || directoryId <= 0) ? null : directoryId;
    }

    /**
     * 某个人的副本编码前缀：{@code u{ownerId}__}（双下划线是系统保留的分隔符）。
     */
    public String namespacePrefix(Long userId) {
        return "u" + userId + CODE_SEPARATOR;
    }

    /**
     * 生成副本编码：{@code prefix + sourceCode}，超过 maxLen 就截断；
     * 如果和已有编码撞了就在后面加 _2/_3…（加完还是不能超过长度）
     */
    public String buildUniqueCode(String prefix, String sourceCode, int maxLen, Predicate<String> exists) {
        String code = sourceCode == null ? "" : sourceCode;
        String base = truncate(prefix + code, maxLen);
        if (!exists.test(base)) {
            return base;
        }
        for (int seq = 2; seq <= CODE_SEQ_MAX; seq++) {
            String suffix = "_" + seq;
            String head = truncate(prefix + code, maxLen - suffix.length());
            String candidate = head + suffix;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new ServiceException("无法为安装副本生成唯一编码，请缩短源编码长度");
    }

    /**
     * 截断到最大长度。
     */
    public String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        int limit = Math.max(0, maxLen);
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public boolean existsChainCode(String code) {
        Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, code));
        return count != null && count > 0;
    }

    /**
     * 找一条能直接复用的副本（decisions §十四-2、§十五-6）：在我名下找一条
     * 从同一条市场源链复制来的、装完之后一直没动过的副本，找到就直接用它，不再复制。
     * <p>
     * 不能靠编码来找：编码只认最基础的那个，带 {@code _2}/{@code _3} 后缀的干净副本会被漏掉，
     * 结果就是每装一次就多复制一条。
     * <p>
     * 有两种副本就算是从同一条源链复制来的也不能复用（返回 null，改走 {@link #buildUniqueCode}
     * 从市场原版重新复制一份）：
     * <ul>
     *   <li>被改过的（{@code update_time <> create_time}，判断方式同 §十五-5）——复用它的话，
     *       新装的链会不声不响地跑用户自己改过的逻辑，而不是市场上过了审的那份，全程没有任何提示；</li>
     *   <li>被标成精选模板的（{@code is_template = '1'}）——发布时会被 validateChainReferences 拒掉，
     *       而报错里看不出来是安装这一步造成的，查起来很费劲。</li>
     * </ul>
     * 查询必须限定创建人是我：source_chain_id 指向的是市场源链，别人装的副本也满足这个条件。
     * 老数据的副本 source_chain_id 是 NULL，认不出是不是同一条源链，就返回 null 另外复制一条
     * （这样做只会多一条副本，不会用错，所以不做数据迁移）。
     */
    public DatabusChain findReusableSameSourceCopy(Long userId, DatabusChain sourceChain) {
        List<DatabusChain> candidates = chainMapper.selectList(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getCreateBy, userId)
            .eq(DatabusChain::getSourceChainId, sourceChain.getId())
            .apply("update_time = create_time")
            .and(w -> w.isNull(DatabusChain::getIsTemplate)
                .or().ne(DatabusChain::getIsTemplate, TEMPLATE_FLAG_YES))
            .orderByAsc(DatabusChain::getId));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 按链路内容（画布 + EL）算一个标识值，内容一样算出来就一样。
     */
    public String chainHash(DatabusChain chain) {
        String raw = nullToEmpty(chain.getCanvasData()) + HASH_SEP + nullToEmpty(chain.getElExpression());
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按组件内容（脚本正文 + 参数 schema + 节点类型 + 配置形态）算一个标识值。
     */
    public String componentHash(DatabusComponent component) {
        String raw = nullToEmpty(component.getScriptBody()) + HASH_SEP
            + nullToEmpty(component.getParamSchema()) + HASH_SEP
            + nullToEmpty(component.getNodeType()) + HASH_SEP
            + nullToEmpty(component.getEditor());
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    public String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 取字符串最后一个分隔符之后的部分（无分隔符则原样返回）。
     */
    public String substringAfterLast(String value, String separator) {
        if (value == null) {
            return null;
        }
        int idx = value.lastIndexOf(separator);
        return idx >= 0 ? value.substring(idx + separator.length()) : value;
    }
}
