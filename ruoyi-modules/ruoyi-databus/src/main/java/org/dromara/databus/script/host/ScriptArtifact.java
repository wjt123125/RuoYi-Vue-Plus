package org.dromara.databus.script.host;

import org.dromara.databus.component.schema.enums.NodeTypeKind;
import org.dromara.databus.component.schema.model.PropSchema;

import java.util.List;

/**
 * 一次脚本编译的产物：加载后的主类、反射物化出的契约信息。
 *
 * <p>保存管线据此回填 databus_component 契约缓存列（node_type/editor/param_schema/data_example），
 * 注册器据此创建实例并挂入 FlowBus。
 *
 * @param primaryClass     唯一带 @DatabusCmp 的主类（必为 LiteFlow 节点组件子类）
 * @param classLoader      本次编译专属类加载器（持有全部编译产物）
 * @param declaredCode     @DatabusCmp.code() 显式声明（空串表示未声明，运行 id 以组件表编码为准）
 * @param nodeType         注解声明的节点类型（已校验与主类父类型一致）
 * @param dataExample      注解 dataExample（空为 null）
 * @param fields           嵌套 Cfg 反射出的字段契约（无 Cfg 时为空列表）
 * @param paramSchemaJson  物化后的 param_schema 列值（<code>{"fields":[...]}</code>，永不为 null）
 * @author databus
 */
public record ScriptArtifact(Class<?> primaryClass,
                             ScriptClassLoader classLoader,
                             String declaredCode,
                             NodeTypeKind nodeType,
                             String dataExample,
                             List<PropSchema> fields,
                             String paramSchemaJson) {
}
