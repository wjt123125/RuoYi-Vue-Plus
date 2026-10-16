package org.dromara.databus.script.host;

import com.yomahub.liteflow.core.NodeComponent;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

/**
 * 脚本工件的专属「图书管理员」：柜子里只放这一版脚本的编译产物
 * （主类、嵌套 Cfg、匿名类），别的一律往上层书库找。
 *
 * <p>「往上找」就是双亲委派：NodeComponent、SDK、注解这些平台类全部由
 * 应用类加载器加载，全场只有一份身份。这条规矩不能破——要是脚本类自己
 * 偷偷再加载一份 NodeComponent，它手里的和 LiteFlow 手里的就是「同名不同人」，
 * 一交接就 ClassCastException。
 *
 * <p>热更的卸载机关也藏在这：每次保存/回滚都新造一个加载器，FlowBus 裸 put
 * 替换节点后，旧加载器失去全部可达引用，GC 会把整代旧类连锅端走，内存不积。
 * 替换瞬间刻意不主动 close——旧节点实例可能正被某个工作线程执行，
 * 把它还要用的类关了就是事故。
 *
 * @author databus
 */
public class ScriptClassLoader extends URLClassLoader {

    static {
        // 并行加载能力：多个线程同时来取类时按类名各自加锁，互不卡
        registerAsParallelCapable();
    }

    /**
     * binaryName → 字节码：这个柜子的全部家当（主类、静态嵌套 Cfg、
     * 匿名/内部类）。构造时 Map.copyOf 拷成不可变快照，外面后来的
     * 改动进不来。
     */
    private final Map<String, byte[]> classBytes;

    /**
     * 名字起成 databus-script 便于线程转储里认亲；URL 数组给空——
     * 本加载器只认内存柜子，不看磁盘。
     */
    public ScriptClassLoader(Map<String, byte[]> classBytes, ClassLoader parent) {
        super("databus-script", new URL[0], parent);
        this.classBytes = Map.copyOf(classBytes);
    }

    /**
     * loadClass 的双亲委派走完（父加载器没找到）才轮到这里：先翻自家柜子，
     * 有字节码就 defineClass 变成真类；没有就交给父类流程——本加载器
     * 没挂任何 URL，实际等于直接抛 ClassNotFoundException。
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classBytes.get(name);
        if (bytes != null) {
            return defineClass(name, bytes, 0, bytes.length);
        }
        return super.findClass(name);
    }
}
