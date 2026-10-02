package org.dromara.databus.component.data;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.component.cfg.FieldMapCfg;
import org.dromara.databus.component.schema.annotation.DatabusCmp;
import org.dromara.databus.context.PathResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段映射组件（注册名 {@code fieldMap}）。
 * <p>
 * 按 mappings 把上游字段逐条搬运到目标路径（平铺字段、原值复制，不做嵌套递归）。
 *
 * <p>支持两种搬运语义（见 {@link FieldMapCfg}）：
 * <ul>
 *   <li>单值搬运（默认，向后兼容）：from / to 均不含 {@code [*]}，原值搬运，
 *       不配 type 时行为完全等同改造前。</li>
 *   <li>数组批量搬运（A3 新增）：from / to 均含 {@code [*]}，逐元素读取、
 *       按索引写入目标数组的对应位置。</li>
 * </ul>
 *
 * <p>可选 {@code type} 转换：int / string / boolean / double，对每个搬运值
 * （含批量分支的每个元素）做转换，失败抛 {@link ServiceException}。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("fieldMap")
@DatabusCmp(
    code = "fieldMap", name = "字段映射", icon = "ph:arrows-left-right", color = "#9c27b0",
    description = "按 mappings 把 from 表达式（{{ $.路径 }}）取值逐条搬到 to 位置名（裸路径）；from/to 同时含 [*] 触发数组批量搬运，可选 type 做类型转换（int/string/boolean/double）",
    cfg = FieldMapCfg.class, sort = 70
)
public class FieldMapComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        FieldMapCfg cfg = this.getCmpData(FieldMapCfg.class);
        if (cfg == null || cfg.getMappings() == null || cfg.getMappings().isEmpty()) {
            throw new ServiceException("字段映射组件缺少 mappings 配置（tag=" + this.getTag() + "）");
        }
        int count = 0;
        for (FieldMapCfg.Mapping mapping : cfg.getMappings()) {
            if (mapping == null || mapping.getFrom() == null || mapping.getTo() == null) {
                throw new ServiceException("字段映射条目缺少 from/to（tag=" + this.getTag() + "）");
            }
            String from = mapping.getFrom();
            String to = mapping.getTo();
            // from 为「要数据」字段：必须是整字段表达式 {{ $.路径 }}；to 为写目标名字，裸路径
            String fromPath = PathResolver.wholeExpression(from);
            if (fromPath == null) {
                throw new ServiceException("字段映射条目 from 必须为 {{ $.路径 }} 形式（tag="
                    + this.getTag() + "）：" + from);
            }
            boolean fromWildcard = fromPath.contains("[*]");
            boolean toWildcard = to.contains("[*]");
            if (fromWildcard && toWildcard) {
                // 情况 a：数组批量搬运（A3 核心新能力）
                count += batchMap(mapping, from, to);
            } else if (fromWildcard) {
                // 情况 b：from 含 [*] 但 to 不含
                throw new ServiceException("字段映射条目 from 含 [*] 但 to 不含 [*]，无法批量写入（tag=" + this.getTag() + "）");
            } else if (toWildcard) {
                // 情况 c：to 含 [*] 但 from 不含
                throw new ServiceException("字段映射条目 to 含 [*] 但 from 不含 [*]，无法批量写入（tag=" + this.getTag() + "）");
            } else {
                // 情况 d：单值搬运（resolve 取值，可选 type 转换）
                Object value = resolveParam(from);
                if (mapping.getType() != null && value != null) {
                    value = convertType(value, mapping.getType());
                }
                save(to, value);
                count++;
            }
        }
        resultSummary("搬运 " + count + " 个字段");
        log.debug("[databus] fieldMap 完成 {} 条映射，tag={}", count, this.getTag());
    }

    /**
     * 数组批量搬运：from/to 均含 {@code [*]}，逐元素读取、按索引写入目标数组的对应位置。
     *
     * @param mapping 配置（含可选 type 转换）
     * @param from   含 {@code [*]} 的源表达式（{@code {{ $.路径 [*] }}}）
     * @param to     含 {@code [*]} 的目标路径（写目标名字，裸路径）
     * @return 恒为 1
     */
    private int batchMap(FieldMapCfg.Mapping mapping, String from, String to) {
        // 整字段表达式直接取列表；路径不存在时 resolve 抛错（不再静默跳过）
        Object resolved = resolveParam(from);
        if (!(resolved instanceof List<?> rawValues)) {
            throw new ServiceException("字段映射批量源表达式未取到数组（tag="
                + this.getTag() + "）：" + from + "，实际=" + resolved);
        }
        List<Object> values = new ArrayList<>();
        rawValues.forEach(values::add);
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (mapping.getType() != null) {
                value = convertType(value, mapping.getType());
            }
            // 把 to 里的 [*] 替换为具体索引（如 $.target.items[*].name → $.target.items[0].name）
            String targetPath = to.replace("[*]", "[" + i + "]");
            save(targetPath, value);
        }
        log.debug("[databus] fieldMap 批量搬运 {} 个元素 from={} to={} tag={}",
            values.size(), from, to, this.getTag());
        return 1;
    }

    /**
     * 类型转换：int / string / boolean / double。
     * <p>value 为 null 时抛异常（不允许把 null 强转）；转换失败包装为 ServiceException。
     *
     * @param value 原值
     * @param type  目标类型（int/string/boolean/double）
     * @return 转换后的值
     */
    private Object convertType(Object value, String type) {
        if (value == null) {
            throw new ServiceException("fieldMap type 转换失败：value 为 null type=" + type);
        }
        try {
            switch (type) {
                case "int":
                    return Integer.parseInt(value.toString());
                case "string":
                    return String.valueOf(value);
                case "boolean":
                    return Boolean.parseBoolean(value.toString());
                case "double":
                    return Double.parseDouble(value.toString());
                default:
                    throw new ServiceException("fieldMap 不支持的 type: " + type + "（支持 int/string/boolean/double）");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (NumberFormatException e) {
            throw new ServiceException("fieldMap type 转换失败 type=" + type + " value=" + value, e);
        } catch (Exception e) {
            throw new ServiceException("fieldMap type 转换失败 type=" + type + " value=" + value, e);
        }
    }
}
