package org.dromara.databus.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeBooleanComponent;

/**
 * 布尔测试组件：恒返回 false，供循环 BREAK 语句的转换测试使用（不会真正跳出循环）。
 *
 * @author databus
 */
@LiteflowComponent("breakCmp")
public class BooleanTestComponent extends NodeBooleanComponent {

    @Override
    public boolean processBoolean() {
        return false;
    }
}
