package org.dromara.databus.service.impl;

import cn.hutool.core.collection.CollUtil;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.domain.bo.AgentContextItemBo;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把前端结构化附件装配成给模型看的文本块。
 * <p>
 * SnailAI 只接收一段 content，所以「附件」只能在服务端渲染成文字。渲染出来的内容是
 * <b>引用材料</b>：每条以「【上下文 N｜类型】」开头，与用户原话（另行以「用户问题：」标出）
 * 明确分开，模型才不会把选中的日志当成用户自己的陈述。
 *
 * @author databus
 */
public final class AgentContextAssembler {

    /**
     * 选区前后各保留的行数：日志动辄上千行，全量塞进去只会挤掉用户真正的问题。
     */
    private static final int KEEP_LINES = 30;

    /**
     * 单条附件的渲染上限（含标题与评论），超出截断，避免一条长日志吃掉整个上下文。
     */
    private static final int ITEM_LIMIT = 6000;

    /**
     * 整个附件块的上限；超出后剩余附件整体省略并给出条数。
     */
    private static final int BLOCK_LIMIT = 20000;

    private static final Map<String, String> TYPE_NAMES = Map.of(
        "terminal-run", "运行日志",
        "preview-run", "试跑结果",
        "preview-result", "试跑结果明细",
        "compile-error", "编译报错",
        "chain", "链路",
        "component", "组件",
        "chat", "会话消息"
    );

    private AgentContextAssembler() {
    }

    /**
     * 装配附件文本块。
     *
     * @param attachmentsJson databus_agent_run.attachments 里的 JSON 数组，可为空
     * @return 给模型看的文本块；没有有效附件或 JSON 不合法时返回 null（不抛异常，附件坏了不该阻断对话）
     */
    public static String assemble(String attachmentsJson) {
        List<AgentContextItemBo> items;
        try {
            items = JsonUtils.parseArray(attachmentsJson, AgentContextItemBo.class);
        } catch (Exception e) {
            return null;
        }
        if (CollUtil.isEmpty(items)) {
            return null;
        }
        StringBuilder block = new StringBuilder();
        int rendered = 0;
        for (int i = 0; i < items.size(); i++) {
            AgentContextItemBo item = items.get(i);
            if (item == null) {
                continue;
            }
            String text = renderItem(rendered + 1, item);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (block.length() > 0 && block.length() + text.length() + 2 > BLOCK_LIMIT) {
                // 半条附件比没有更糟：模型会把截断处的半句话当事实，所以整条停在这里
                block.append("\n\n（其余 ").append(items.size() - rendered).append(" 条上下文已省略）");
                return block.toString();
            }
            if (block.length() > 0) {
                block.append("\n\n");
            }
            block.append(text);
            rendered++;
        }
        return block.length() == 0 ? null : block.toString();
    }

    /**
     * 渲染一条附件：来源说明 → 用户评论 → 原文（资源引用不出原文，只附标识）。
     *
     * @param index 展示序号（从 1 开始）
     * @param item  附件
     * @return 渲染结果
     */
    private static String renderItem(int index, AgentContextItemBo item) {
        String typeName = typeName(item.getType());
        String title = firstText(item.getTitle(), item.getPreview(), typeName);
        StringBuilder text = new StringBuilder();
        text.append("【上下文 ").append(index).append("｜").append(typeName).append("】").append(title);

        String source = sourceMark(item.getSource());
        if (source != null) {
            text.append('\n').append(source);
        }

        boolean resource = "resource".equalsIgnoreCase(item.getKind());
        if (resource) {
            String mark = resourceMark(item.getPayload());
            if (mark != null) {
                text.append('\n').append(mark);
            }
        }
        if (StringUtils.hasText(item.getNote())) {
            text.append("\n我的评论：").append(item.getNote().trim());
        }
        if (!resource) {
            Excerpt excerpt = renderExcerpt(item.getPayload());
            if (excerpt != null) {
                text.append('\n').append(excerpt.header()).append('\n').append(excerpt.body());
            }
        }

        String result = text.toString();
        if (result.length() > ITEM_LIMIT) {
            result = result.substring(0, ITEM_LIMIT) + "\n（已截断）";
        }
        return result;
    }

    /**
     * 渲染 excerpt 正文：有全文和选区偏移就按行裁出选区附近的片段，否则直接用前端给的 text。
     *
     * @param payload 附件正文
     * @return 原文头与正文；没有正文返回 null
     */
    private static Excerpt renderExcerpt(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        String text = str(payload.get("text"));
        String fullText = str(payload.get("fullText"));
        int offset = intValue(payload.get("offset"), -1);
        if (StringUtils.hasText(fullText) && offset >= 0 && offset <= fullText.length()) {
            return sliceByLine(fullText, offset, intValue(payload.get("length"), 0), text);
        }
        return StringUtils.hasText(text) ? new Excerpt("原文：", text) : null;
    }

    /**
     * 按行切出覆盖选区的片段，前后各留 {@link #KEEP_LINES} 行，省掉的方向在对应位置标行数。
     *
     * @param fullText 容器全文
     * @param offset   选区起点
     * @param length   选区长度
     * @param fallback 选区定位失败时的兜底文本（前端已截好的那段）
     * @return 原文头与正文
     */
    private static Excerpt sliceByLine(String fullText, int offset, int length, String fallback) {
        String[] lines = fullText.split("\n", -1);
        int[] starts = new int[lines.length];
        int cursor = 0;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = cursor;
            cursor += lines[i].length() + 1;
        }
        int fromLine = Math.max(0, lineAt(starts, offset) - KEEP_LINES);
        // 用 long 算选区终点：offset/length 来自前端，直接相加可能溢出成负数
        long end = (long) offset + Math.max(length, 0);
        int lastPos = (int) Math.min(fullText.length(), Math.max(offset, end - 1));
        int toLine = Math.min(lines.length - 1, lineAt(starts, lastPos) + KEEP_LINES);

        StringBuilder body = new StringBuilder();
        for (int i = fromLine; i <= toLine; i++) {
            if (i > fromLine) {
                body.append('\n');
            }
            body.append(lines[i]);
        }
        if (body.length() == 0) {
            return StringUtils.hasText(fallback) ? new Excerpt("原文：", fallback) : null;
        }
        int omittedBefore = fromLine;
        int omittedAfter = lines.length - 1 - toLine;
        String header = omittedBefore > 0 ? "原文（已省略前 " + omittedBefore + " 行）：" : "原文：";
        if (omittedAfter > 0) {
            body.append('\n').append("（已省略后 ").append(omittedAfter).append(" 行）");
        }
        return new Excerpt(header, body.toString());
    }

    /**
     * 定位字符偏移落在第几行。
     *
     * @param starts 各行起始偏移
     * @param pos    字符偏移
     * @return 行号（0 起）
     */
    private static int lineAt(int[] starts, int pos) {
        int line = 0;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= pos) {
                line = i;
            } else {
                break;
            }
        }
        return line;
    }

    /**
     * 渲染来源锚点：链路/组件标识、节点名、记录号。
     * <p>
     * 只带来源说明，不复制正文：模型需要现状时会用工具按标识去查。
     *
     * @param source 来源锚点
     * @return 来源行；没有可用锚点时返回 null
     */
    private static String sourceMark(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        String assetType = str(source.get("assetType"));
        String assetName = str(source.get("assetName"));
        String assetId = str(source.get("assetId"));
        if (StringUtils.hasText(assetName) || StringUtils.hasText(assetId)) {
            String label = switch (assetType == null ? "" : assetType) {
                case "chain" -> "链路";
                case "component" -> "组件";
                case "execution-record" -> "执行记录";
                default -> "来源";
            };
            StringBuilder part = new StringBuilder(label);
            if (StringUtils.hasText(assetName)) {
                part.append("「").append(assetName.trim()).append("」");
            }
            if (StringUtils.hasText(assetId)) {
                part.append(" id=").append(assetId);
            }
            parts.add(part.toString());
        }
        String nodeName = str(source.get("nodeName"));
        if (StringUtils.hasText(nodeName)) {
            parts.add("节点 " + nodeName.trim());
        }
        String recordId = str(source.get("recordId"));
        if (StringUtils.hasText(recordId)) {
            parts.add("记录 " + recordId);
        }
        return parts.isEmpty() ? null : "来源：" + String.join("，", parts);
    }

    /**
     * 渲染资源引用标识：只给 id / code，不复制正文。
     *
     * @param payload 附件正文
     * @return 标识行；id 与 code 都没有时返回 null
     */
    private static String resourceMark(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        String id = str(payload.get("id"));
        if (StringUtils.hasText(id)) {
            parts.add("id=" + id);
        }
        String code = str(payload.get("code"));
        if (StringUtils.hasText(code)) {
            parts.add("code=" + code);
        }
        return parts.isEmpty() ? null : "标识：" + String.join(", ", parts);
    }

    /**
     * provider 键转中文名；不认识的类型保留原值，便于新增 provider 时先跑通再补文案。
     *
     * @param type provider 键
     * @return 类型展示名
     */
    private static String typeName(String type) {
        if (!StringUtils.hasText(type)) {
            return "附件";
        }
        return TYPE_NAMES.getOrDefault(type, type);
    }

    /**
     * 取第一个非空文本。
     *
     * @param values 候选值
     * @return 文本
     */
    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * 把 payload 里的值转成字符串（JSON 数字可能反序列化成 Integer 或 Long）。
     *
     * @param value 原值
     * @return 字符串，null 转 null
     */
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 把 payload 里的值转成 int。
     *
     * @param value        原值
     * @param defaultValue 转换失败时的默认值
     * @return 整数
     */
    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 渲染好的原文：头部说明 + 正文。
     *
     * @param header 头部说明（含省略行数）
     * @param body   正文
     */
    private record Excerpt(String header, String body) {
    }

}