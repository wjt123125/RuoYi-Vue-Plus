package org.dromara.databus.script.host;

import com.yomahub.liteflow.core.NodeComponent;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

/**
 * 脚本工件的内存类加载器：父加载器优先（平台类全部来自应用 CL，保证
 * {@link NodeComponent}、SDK、注解等类型与宿主同一身份），脚本编译产物
 * （org.dromara.databus.script 包名下的主类与嵌套 Cfg 等）只在本加载器内定义。
 *
 * <p>热更模型：每次保存/回滚编译产生一个全新的 {@link ScriptClassLoader} 实例，
 * FlowBus 裸 put 替换节点后，旧加载器仅被旧节点实例短暂引用，替换后无可达路径，
 * 由 GC 卸载（不主动 close：旧实例在替换瞬间可能正被工作线程执行）。
 *
 * @author databus
 */
public class ScriptClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    /**
     * binaryName → class 字节码（含主类、静态嵌套 Cfg、匿名/内部类）。
     */
    private final Map<String, byte[]> classBytes;

    public ScriptClassLoader(Map<String, byte[]> classBytes, ClassLoader parent) {
        super("databus-script", new URL[0], parent);
        this.classBytes = Map.copyOf(classBytes);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classBytes.get(name);
        if (bytes != null) {
            return defineClass(name, bytes, 0, bytes.length);
        }
        return super.findClass(name);
    }
}
