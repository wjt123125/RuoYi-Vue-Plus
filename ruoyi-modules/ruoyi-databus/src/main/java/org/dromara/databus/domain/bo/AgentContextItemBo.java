package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 前端结构化附件，后端据此装配给模型的文本。
 * <p>
 * 只做透传与校验：payload / source 的形状由前端各 provider 定义，后端装配时按需读取，
 * 不认识的键原样保留（附件整体以 JSON 落 databus_agent_run.attachments，刷新时原样回显）。
 *
 * @author databus
 */
@Data
public class AgentContextItemBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 前端生成的 id（会话内去重与回执）
     */
    private String id;

    /**
     * excerpt（选中文字）/ resource（资源引用）
     */
    private String kind;

    /**
     * provider 键：terminal-run / preview-run / compile-error / chain / component
     */
    private String type;

    /**
     * 卡片标题，一行
     */
    @Size(max = 200, message = "附件标题长度不能超过{max}个字符")
    private String title;

    /**
     * 卡片副文案，前端已截断
     */
    @Size(max = 300, message = "附件摘要长度不能超过{max}个字符")
    private String preview;

    /**
     * 用户对这条上下文的评论（可空）
     */
    @Size(max = 500, message = "附件评论长度不能超过{max}个字符")
    private String note;

    /**
     * 正文与标识：excerpt 带 text/fullText/offset/length，resource 带 id/code
     */
    private Map<String, Object> payload;

    /**
     * 来源锚点（assetType / assetId / nodeName / recordId / runId / route 等）
     */
    private Map<String, Object> source;

    /**
     * 去重键：来源锚点 + 文本位置
     */
    private String fingerprint;

}