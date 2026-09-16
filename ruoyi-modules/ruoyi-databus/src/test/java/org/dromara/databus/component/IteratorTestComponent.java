package org.dromara.databus.component;

import cn.hutool.core.collection.ListUtil;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeIteratorComponent;

import java.util.Iterator;
import java.util.List;

/**
 * 迭代循环测试组件：返回固定三元素迭代器，供 ITERATOR parser 往返测试使用。
 *
 * @author databus
 */
@LiteflowComponent("iteratorCmp")
public class IteratorTestComponent extends NodeIteratorComponent {

    @Override
    public Iterator<?> processIterator() {
        List<String> list = ListUtil.toList("111", "222", "333");
        return list.iterator();
    }
}
