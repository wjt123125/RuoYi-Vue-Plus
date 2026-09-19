package org.dromara.databus.component.data;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据补丁组件（注册名 {@code dataPatch}）。
 * <p>
 * 按 JSON Merge Patch 语义把补丁字段合并到 target 命中的每个对象：
 * 嵌套对象做深合并（保留目标已有兄弟键），叶子覆盖、缺失键新增，未声明字段原样保留。
 * 典型用途是 boQuery 查出记录后修改若干字段再交给 boUpdate 整体回写（查 → 改 → 写往返）。
 *
 * <p><b>实现机制（jayway 2.10.0 + Jackson provider 实测，勿改成拼叶子路径 set）：</b>
 * jayway {@code set} 只能改求值时已存在的属性节点，不创建缺失叶子/数组
 * （通配、过滤、空数组、缺失父路径均抛 PathNotFoundException）；而
 * {@code DatabusContext.write} 失败后的 createPath 兜底只认 {@code [数字]} 索引，
 * 不认 {@code [*]} / {@code [?(...)]}，拼通配叶子路径会写出字面垃圾键。
 * 故本组件不拼叶子路径，而是 read 出 target 命中的对象——Jackson provider 返回的
 * 是文档底层 {@code LinkedHashMap} 引用（filter/[*] 命中元素同为引用，已实测），
 * 直接在引用上递归 putAll，改动即文档本身；再用确定路径写 patchedCount。
 *
 * <p>零命中（路径不存在、过滤无结果、空数组）直接抛错，避免静默漏改。
 *
 * <p>命中对象数存入 {@code $.<tag>.patchedCount}。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("dataPatch")
public class DataPatchComponent extends DatabusNodeComponent {

    /** 补丁键（含嵌套层级）只接受安全标识符，拒绝点号/通配/表达式，杜绝歧义。 */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public void process() {
        DataPatchCfg cfg = this.getCmpData(DataPatchCfg.class);
        if (cfg == null) {
            throw new ServiceException("数据补丁组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("数据补丁组件缺少数据空间标识（tag）");
        }
        if (cfg.getTarget() == null || cfg.getTarget().isBlank()) {
            throw new ServiceException("数据补丁组件缺少 target 配置（tag=" + tag + "）");
        }
        if (cfg.getPatch() == null || cfg.getPatch().isEmpty()) {
            throw new ServiceException("数据补丁组件缺少非空 patch 配置（tag=" + tag + "）");
        }
        String target = cfg.getTarget();

        // 零命中 / 非对象目标直接报错（update 语义安全网）。read 返回底层 Map 引用。
        Object hit = getOptional(target);
        List<Map<String, Object>> targets = new ArrayList<>();
        if (hit instanceof Map<?, ?> map) {
            targets.add(asObjMap(map, target, tag));
        } else if (hit instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new ServiceException("数据补丁目标为空数组，没有可打补丁的记录: " + target);
            }
            for (int i = 0; i < list.size(); i++) {
                Object el = list.get(i);
                if (!(el instanceof Map<?, ?>)) {
                    throw new ServiceException("数据补丁 target 命中的数组元素不是对象: " + target + "[" + i + "]");
                }
                targets.add(asObjMap((Map<?, ?>) el, target + "[" + i + "]", tag));
            }
        } else if (hit == null) {
            throw new ServiceException("数据补丁目标无命中（路径不存在或过滤无结果）: " + target);
        } else {
            throw new ServiceException("数据补丁 target 必须指向对象或对象数组，实际为: "
                + hit.getClass().getSimpleName() + "（" + target + "）");
        }

        // 在每个命中对象的引用上做深合并（引用即文档，putAll 直接生效，支持新增缺失键）
        for (Map<String, Object> obj : targets) {
            deepMerge(obj, cfg.getPatch(), target, tag);
        }

        save("$." + tag + ".patchedCount", targets.size());
        resultSummary("补丁命中 " + targets.size() + " 个对象，合并 " + cfg.getPatch().size() + " 个字段");
        log.info("[databus] dataPatch 完成 tag={} target={} 命中 {} 个对象，补丁 {} 个顶层字段",
            tag, target, targets.size(), cfg.getPatch().size());
    }

    /**
     * 递归深合并补丁到目标对象引用：
     * <ul>
     *   <li>补丁值是 Map → 目标同键已是 Map 则下钻合并，否则新建空 Map 后合并（支持新增嵌套结构）；</li>
     *   <li>其余类型（常量/裸路径/混合模板/数组/null）→ resolveParam 后整体覆盖叶子。</li>
     * </ul>
     *
     * @param dst    目标对象（文档底层 Map 引用）
     * @param patch  当前层补丁
     * @param target 根 target 路径（仅用于错误信息）
     * @param tag    组件数据空间标识（仅用于错误信息）
     */
    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> dst, Map<String, Object> patch, String target, String tag) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            if (key == null || !SAFE_KEY.matcher(key).matches()) {
                throw new ServiceException("数据补丁字段名非法（只允许字母/数字/下划线且数字不开头）: "
                    + key + "（tag=" + tag + "）");
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> subPatch) {
                Object existing = dst.get(key);
                Map<String, Object> child;
                if (existing instanceof Map<?, ?> existingMap) {
                    child = (Map<String, Object>) existingMap;
                } else {
                    child = new LinkedHashMap<>();
                    dst.put(key, child);
                }
                deepMerge(child, (Map<String, Object>) subPatch, target, tag);
            } else {
                dst.put(key, resolveParam(value));
            }
        }
    }

    /** 把命中的 Map 收窄为 {@code Map<String,Object>}，键非 String 直接报错。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjMap(Map<?, ?> map, String where, String tag) {
        for (Object k : map.keySet()) {
            if (!(k instanceof String)) {
                throw new ServiceException("数据补丁目标对象含非字符串键，无法合并: " + where + "（tag=" + tag + "）");
            }
        }
        return (Map<String, Object>) map;
    }
}
