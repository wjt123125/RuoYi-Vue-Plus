/**
 * EL 表达式解析与画布数据双向转换引擎（参考 liteflow-editor-core）。
 *
 * <p>包内结构与阅读顺序（建议按 1→2→3 顺序读源码）：</p>
 * <ol>
 *   <li><b>bean</b> —— 数据模型：{@link org.dromara.databus.el.bean.CmpProperty}
 *       （画布组件树节点，先看懂它）、{@link org.dromara.databus.el.bean.Properties}（id/tag/data）、
 *       {@link org.dromara.databus.el.bean.ELInfo}（EL 文本信封）；</li>
 *   <li><b>parser.base</b> —— 骨架：{@link org.dromara.databus.el.parser.base.ExpressParser}
 *       （接口 + EL 语法模板常量）、{@link org.dromara.databus.el.parser.base.AbstractExpressParser}
 *       （双向转换核心：模板法五步曲 + 递归枢纽）；</li>
 *   <li><b>parser.el</b> —— 每种 EL 关键字一个解析器（THEN/WHEN/IF/SWITCH/
 *       FOR/WHILE/ITERATOR/CATCH/AND|OR/NOT），靠
 *       {@link org.dromara.databus.el.parser.factory.ExpressParserFactory} 自动注册、
 *       {@link org.dromara.databus.el.parser.selector.ParserSelector} 路由。</li>
 * </ol>
 *
 * <p>技术文档：见 {@code docs/wiki/databus-el-express-parser.md}。</p>
 *
 * @author databus
 */
package org.dromara.databus.el;
