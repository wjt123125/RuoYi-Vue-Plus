package org.dromara.databus.agent;

/**
 * 工具层共享的文本小工具。
 * <p>
 * 拆分工具集后多个协作者都需要「空白串转 null」，集中一处，避免各文件重复定义同名 helper
 * 或让不相干的类互相静态引用。
 */
final class AgentToolText {

    private AgentToolText() {
    }

    /**
     * 去首尾空白；null、空串、全空白统一返回 null。
     *
     * @param text 原始文本
     * @return 去空白后的文本，无有效内容返回 null
     */
    static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
