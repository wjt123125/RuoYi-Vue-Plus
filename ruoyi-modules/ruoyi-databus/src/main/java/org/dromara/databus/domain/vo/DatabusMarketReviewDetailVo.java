package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 审核详情视图对象（decisions §十四-7 嵌套审核 gate 的审核界面数据源）。
 * <p>
 * 主体 item（受审物料行）＋闭包依赖状态树：已审 ONLINE 置绿、待审 PENDING 可点、
 * 驳回 REJECTED 带理由（remark）。仅 CHAIN 物料有依赖树，组件/连接器为空列表。
 * <p>
 * 审核界面前端交互（拒绝不中断/进度保存/理由聚合）分期另启，本期只交付本查询端点。
 *
 * @author databus
 * @since 2026/10/23
 */
@Data
public class DatabusMarketReviewDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 受审主体物料行
     */
    private DatabusMarketItemVo item;

    /**
     * 闭包依赖状态树（主链的全部传递子链＋闭包链引用的全部业务组件；主链自身即主体，不重复列出）
     */
    private List<DependencyVo> dependencies;

    /**
     * 内容预览（P1 审核可见性，decisions §十四-7 扩展：解「干审」——审核者不看内容无法背书）。
     * CHAIN = 画布节点清单（类型/名称/编码/引用关系，不展开脚本）；COMPONENT = 关键信息（作者/版本/语言/描述）。
     */
    private PreviewVo preview;

    /**
     * 内容预览视图对象（P1 元信息预览：审核者可见的内容概要，不展开实现细节）。
     */
    @Data
    public static class PreviewVo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 画布节点清单（仅 CHAIN：遍历 cmp_property 树，结构关键字与业务叶子并列，展示链路骨架）
         */
        private List<NodeVo> nodes;

        /**
         * 源资源编码（COMPONENT＝组件编码，前端按 code 开组件详情 tab；CHAIN 为空——
         * 链路查看按 sourceId 开画布，无需编码，P1 看得见）
         */
        private String code;

        /**
         * 作者（COMPONENT 关键信息；CHAIN 为空）
         */
        private String author;

        /**
         * 版本（COMPONENT 关键信息）
         */
        private String version;

        /**
         * 脚本语言（COMPONENT 关键信息）
         */
        private String scriptLang;

        /**
         * 描述（COMPONENT 关键信息）
         */
        private String description;
    }

    /**
     * 画布节点概要（审核可见，不携带实现细节）。
     */
    @Data
    public static class NodeVo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 节点类型（业务组件节点类型 / EL 结构关键字如 THEN/IF/CHAIN）
         */
        private String type;

        /**
         * 节点编码（业务组件编码；结构关键字为空）
         */
        private String code;

        /**
         * 节点名称（业务组件行名；查无行时退化为编码）
         */
        private String name;

        /**
         * 是否结构关键字（true=EL 表达式结构节点，false=业务组件/链引用叶子）
         */
        private boolean structure;

        /**
         * 引用层级（2026-10-24 实现预览可读性：根 0，children/condition 逐级 +1；
         * 前端按 depth 缩进还原树形，口径与审核依赖树的 depth 一致）
         */
        private Integer depth;
    }

    /**
     * 依赖状态条目：一项闭包依赖（链或组件）的市场状态。
     */
    @Data
    public static class DependencyVo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 依赖类型（CHAIN / COMPONENT）
         */
        private String resourceType;

        /**
         * 源资源 id（链路主键 / 组件行 id；悬空引用查无行时为 null）
         */
        private Long sourceId;

        /**
         * 源资源编码（链 chainCode / 组件 componentCode；悬空组件为引用编码）——
         * 前端「查看」跳转工作台 tab 的钥匙：链路按 id 开画布、组件按 code 开详情（P1 看得见）
         */
        private String code;

        /**
         * 名称（链名 / 组件名；悬空引用退化为引用编码）
         */
        private String name;

        /**
         * 对应市场物料 id（未上架为 null）
         */
        private Long marketItemId;

        /**
         * 市场物料上架者 id（2026-10-24 闭包联审：前端就地审依赖时判禁自审——
         * 依赖单据 owner 是当前治理者本人时隐藏通过/驳回按钮）
         */
        private Long ownerId;

        /**
         * 市场状态（ONLINE / PENDING / REJECTED / OFFLINE；null＝未上架）
         */
        private String marketStatus;

        /**
         * 审核备注（REJECTED 时为驳回理由）
         */
        private String remark;

        /**
         * 修改说明（P1 驳回-重提闭环：作者重提时填写，审核者复核用）
         */
        private String modifyNote;

        /**
         * 引用树缩进层级（2026-10-24 闭包联审：依赖列表按引用树先序展开——
         * 主链的直接依赖为 1，每下钻一层 +1；前端按 depth 缩进渲染出层级感，
         * 列表顺序即结构顺序，审核顺序靠 blockedBy 行内提示引导）
         */
        private Integer depth;

        /**
         * 前置未过审依赖名清单（2026-10-24 闭包联审：仅 CHAIN 依赖有值——其闭包内
         * 尚未 ONLINE 的链/组件名称；空或非空列表为 null 表示可直接审。
         * 让审核者点开前就知道先审谁，不再靠 gate 报错试错）
         */
        private List<String> blockedBy;
    }

}
