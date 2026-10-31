package org.dromara.databus.connector.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 连接器包专用类加载器：child-first（先找自己 {@code lib/}，找不到才问父加载器）。
 *
 * <p>对应方案 §4.2。每个连接器包各自一个加载器，规则相同。与 parent-first 的
 * {@code URLClassLoader} 相反：包内那份驱动/实现优先，父加载器（应用类加载器）垫底，
 * 保证「包里那份驱动真的生效」，而不是被平台 pom 里早有的一份悄悄盖掉。
 *
 * <p>两条防线（§2.2 红线）：
 * <ul>
 *   <li>{@link #PARENT_FIRST}：类名前缀表，命中即先问父加载器（JDK 自身类、api 契约
 *       模块、Spring、日志、两套 Jackson、HikariCP、Lombok——都是「基础设施不是内容」，
 *       防契约双份与日志框架分裂，同时运行时兜底：即使包作者绕过 CI 把这些类塞进包，
 *       父那一份也一定赢）；</li>
 *   <li>{@link #PARENT_FIRST_PATHS}：同上前缀的资源路径形式（{@code '.'} 换 {@code '/'}），
 *       资源查找必须与类查找同策略，否则「新代码配旧文案」（如驱动自带的
 *       {@code LocalizedErrorMessages.properties} / {@code messages_*.properties}）。</li>
 * </ul>
 *
 * @author databus
 */
public class PluginClassLoader extends URLClassLoader {

    /** 这些包一律先问父加载器，包内带了也不算——防契约双份与日志框架分裂（§2.2 红线）。 */
    private static final String[] PARENT_FIRST = {
        "java.", "javax.", "jdk.", "sun.",          // JDK 自己的类
        "org.dromara.databus.connector.",           // api 契约模块
        "org.springframework.",                      // 平台已有；连接器可借用，不许自带
        "org.slf4j.", "org.apache.logging.log4j.",  // 日志门面与实现
        "ch.qos.logback.", "org.apache.commons.logging.",
        "tools.jackson.", "com.fasterxml.jackson.", // 平台两套 Jackson 都在父加载器
        "com.zaxxer.hikari.",                        // 连接池是基础设施不是内容（§2.2）
        "lombok.",
    };

    /** 同上前缀，资源路径形式（'.' → '/'）。 */
    private static final String[] PARENT_FIRST_PATHS = {
        "java/", "javax/", "jdk/", "sun/",
        "org/dromara/databus/connector/",
        "org/springframework/",
        "org/slf4j/", "org/apache/logging/log4j/",
        "ch/qos/logback/", "org/apache/commons/logging/",
        "tools/jackson/", "com/fasterxml/jackson/",
        "com/zaxxer/hikari/",
    };

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    private static boolean parentFirst(String name) {
        for (String p : PARENT_FIRST) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean parentFirstPath(String path) {
        for (String p : PARENT_FIRST_PATHS) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                return c;
            }
            if (parentFirst(name)) {
                return super.loadClass(name, resolve);
            }
            try {
                c = findClass(name);                      // 自己的 lib/ 优先
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);    // 自己没有 → 委派父
            }
        }
    }

    // 类走 child-first，资源也必须 child-first，否则「新代码配旧文案」
    @Override
    public URL getResource(String name) {
        if (parentFirstPath(name)) {
            return super.getResource(name);
        }
        URL u = findResource(name);
        return u != null ? u : super.getResource(name);
    }

    /**
     * 只列<b>包内自带</b>的资源（不问父加载器），供装载校验与驱动清单使用。
     *
     * <p>与 {@link #getResources} 的区别：那个是 child-first 合并视图，父加载器那份也会混进来。
     * 判断「这个包自己声明了什么」必须只看自己的 URL，否则平台的 SPI 声明会被误算成包内声明。
     */
    public List<URL> ownResources(String name) throws IOException {
        return Collections.list(findResources(name));
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (parentFirstPath(name)) {
            return super.getResources(name);
        }
        List<URL> all = new ArrayList<>(Collections.list(findResources(name)));
        all.addAll(Collections.list(super.getResources(name)));   // 包里的排前面
        return Collections.enumeration(all);
    }
}