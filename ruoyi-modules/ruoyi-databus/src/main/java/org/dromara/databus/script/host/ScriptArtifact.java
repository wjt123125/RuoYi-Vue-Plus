package org.dromara.databus.script.host;

import org.dromara.databus.component.schema.enums.NodeTypeKind;
import org.dromara.databus.component.schema.model.PropSchema;

import java.util.List;

/**
 * 编译车间的「成品提货单」：一次编译产出的全部家当——主类、随身加载器、
 * 反射物化出的契约——都开在这张单子上，保存管线和登记处各取所需。
 *
 * <p>保存管线据此回填 databus_component 的契约缓存列
 * （node_type/editor/param_schema/data_example），登记处据此造实例、挂 FlowBus。
 *
 * @param primaryClass    唯一打了 @DatabusCmp 的主类（必为 LiteFlow 节点组件子类——体检过的）
 * @param classLoader     本次编译专属的加载器（装着全部编译产物，实例都从它身上来）
 * @param declaredCode    注解里显式写的 code（空串 = 没写，实际运行 id 以组件台账的编码为准）
 * @param nodeType        注解声明的节点类型（已校验与主类父类对得上）
 * @param dataExample     注解里的 dataExample（没写就是 null）
 * @param fields          嵌套 Cfg 反射出的字段契约（表单长什么样靠它；无 Cfg 时为空列表）
 * @param paramSchemaJson 物化好的 param_schema 列值（<code>{"fields":[...]}</code>，
 *                        永不为 null——空表单也是合法表单）
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
