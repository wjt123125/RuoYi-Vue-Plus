package org.dromara.databus.script.host;

import com.yomahub.liteflow.core.NodeBooleanComponent;
import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.core.NodeForComponent;
import com.yomahub.liteflow.core.NodeIteratorComponent;
import com.yomahub.liteflow.core.NodeSwitchComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.component.schema.annotation.DatabusCmp;
import org.dromara.databus.component.schema.enums.NodeTypeKind;
import org.dromara.databus.component.schema.introspect.CfgIntrospector;
import org.dromara.databus.component.schema.model.PropSchema;
import org.dromara.databus.domain.vo.ComponentSchemaBody;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 脚本原子的「编译车间」。进来的是一段完整 Java 源码（一个组件主类 + 内嵌的静态 Cfg 配置类），
 * 出去的是一个 {@link ScriptArtifact}：用 JDK 自带的编译器（javac，从 javax.tools 拿到）
 * 在内存里把源码编成字节码，用一个全新的 {@link ScriptClassLoader} 把类加载出来，
 * 再反射注解，把「组件叫什么、节点什么类型、参数表单长什么样」这些信息
 * （文档里叫「契约」）整理成型。
 *
 * <p>全程纯内存：源码不写临时文件、字节码不落盘。编不过就带着行列号报错，
 * 编过了类只在内存里活着——这是「能保存的就能跑」且不弄脏磁盘的基础。
 *
 * <p>给 javac 摆「书架」（classpath：编译时允许看见哪些类）要分两种住法：
 * <ul>
 *   <li>开发环境（IDE 跑）：依赖本来就是磁盘上散着的一堆 jar 和 classes 目录，
 *       顺着类加载器链加 {@code java.class.path} 直接指给它看；</li>
 *   <li>Spring Boot fat jar 部署：所有依赖 jar 被塞进大 jar 内部（jar 里套 jar），
 *       而 javac 只认磁盘上真实存在的文件夹和 jar 文件，打不开套娃。这活没人代劳——
 *       javac 不会、Spring Boot 也不会，所以首次编译时我们自己把 BOOT-INF/classes
 *       和 BOOT-INF/lib 解压到临时缓存目录（目录名按 jar 名+长度+修改时间版本化，
 *       换部署包自动换新目录），摊平了拼给 javac，之后一直复用这份缓存。</li>
 * </ul>
 *
 * <p>故意开着注解处理（不传 -proc:none）：从存量 Java 组件搬迁过来的 Cfg 还用着 Lombok
 * 这类注解，得让编译期照常处理，不然编不过。
 *
 * @author databus
 */
@Slf4j
@Component
public class JavaSourceCompiler {

    /**
     * 顶级 public 类名抓取（取第一个匹配；嵌套 public static class 出现在其后）。
     */
    private static final Pattern PUBLIC_CLASS_PATTERN =
        Pattern.compile("public\\s+(?:final\\s+|abstract\\s+|strictfp\\s+)*class\\s+(\\w+)");

    private volatile List<String> classpathCache;

    /**
     * 编译一段脚本源码，顺带把注解里的契约反射出来。
     *
     * <p>流程是「先编后查」：内存编译 → 加载 → 挑出 @DatabusCmp 主类 →
     * 体检（主类唯一、父类和节点类型对得上、Cfg 找得到）→ 反射 Cfg 字段成表单契约。
     * 「编不过」和「体检不过」分开报：前者带行列号，是给编辑器里改代码的人看的；
     * 后者是结构问题（比如忘了打 @DatabusCmp），改语法没用，得改结构。
     *
     * @param source 完整 Java 源码（一个 public 主类 + 内嵌 Cfg）
     * @return 脚本工件：主类、本次编译专属的类加载器、反射出的契约
     * @throws ScriptCompileException 编译失败，诊断带行列号（供编辑器回显定位）
     * @throws ServiceException       结构校验失败（如缺 @DatabusCmp 主类、父类与 nodeType 不符）
     */
    public ScriptArtifact compile(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ServiceException("当前运行环境缺少 Java 编译器（javax.tools.JavaCompiler），请使用 JDK 运行服务端");
        }
        String publicClassName = detectPublicClassName(source);
        String fileName = publicClassName != null ? publicClassName + ".java" : "DatabusScript.java";

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(
            diagnostics, null, StandardCharsets.UTF_8);
        Map<String, byte[]> classBytes = new ConcurrentHashMap<>();
        StringWriter compilerOut = new StringWriter();
        boolean success;
        try (MemoryJavaFileManager fileManager = new MemoryJavaFileManager(standard, classBytes)) {
            List<String> options = new ArrayList<>();
            options.add("-encoding");
            options.add("UTF-8");
            options.add("-classpath");
            options.add(buildClasspath());
            JavaFileObject sourceFile = new SourceFileObject(fileName, source);
            success = compiler.getTask(compilerOut, fileManager, diagnostics, options, null,
                List.of(sourceFile)).call();
        } catch (IOException e) {
            throw new ServiceException("脚本编译环境初始化失败：" + e.getMessage());
        }
        if (!success) {
            List<ScriptDiagnostic> list = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                list.add(new ScriptDiagnostic(d.getKind().name(), d.getLineNumber(),
                    d.getColumnNumber(), d.getMessage(null)));
            }
            String summary = summarizeErrors(list, compilerOut.toString());
            log.warn("[databus-script] 脚本编译失败：{}", summary);
            throw new ScriptCompileException(summary, list);
        }

        return loadAndIntrospect(classBytes);
    }

    // ---------------------------------------------------------------------
    // 加载与结构校验
    // ---------------------------------------------------------------------

    /**
     * 字节码变成类、类变成工件：编译车间的后半段流水线。
     *
     * <p>先造一个 {@link ScriptClassLoader} 把内存字节码 defineClass 成真类；再在
     * 顶层类里找唯一那个打了 @DatabusCmp 的当主类——找着好几个或一个没有都算
     * 结构错误，当场拒绝；主类还得是能 new 的普通类（注解、接口、枚举、抽象类
     * 都不行）。接着过体检（父类和节点类型对得上、Cfg 解析得出来），最后反射
     * Cfg 字段物化出参数表单契约，连同主类、加载器打包成 {@link ScriptArtifact}
     * 交给登记处。Cfg 没解析出来不报错，表单退化成 JSON 编辑，但日志里留一句提醒。
     *
     * @param classBytes 编译产出的全部字节码（binaryName → byte[]，含主类、嵌套 Cfg、匿名类）
     * @return 反射完成的脚本工件
     */
    private ScriptArtifact loadAndIntrospect(Map<String, byte[]> classBytes) {
        ScriptClassLoader classLoader = new ScriptClassLoader(classBytes,
            JavaSourceCompiler.class.getClassLoader());
        Class<?> primary = null;
        List<String> topLevel = classBytes.keySet().stream()
            .filter(name -> !name.contains("$"))
            .sorted()
            .toList();
        for (String name : topLevel) {
            try {
                Class<?> candidate = Class.forName(name, false, classLoader);
                if (AnnotationUtils.findAnnotation(candidate, DatabusCmp.class) != null) {
                    if (primary != null) {
                        throw new ServiceException("脚本中存在多个 @DatabusCmp 主类："
                            + primary.getName() + "、" + candidate.getName() + "；一段脚本只允许一个组件主类");
                    }
                    primary = candidate;
                }
            } catch (ClassNotFoundException e) {
                throw new ServiceException("脚本类加载失败：" + name + "（" + e.getMessage() + "）");
            }
        }
        if (primary == null) {
            throw new ServiceException("脚本中找不到 @DatabusCmp 主类：组件主类必须标注 @DatabusCmp 并继承 LiteFlow 节点组件基类");
        }
        if (primary.isAnnotation() || primary.isInterface() || primary.isEnum()
            || java.lang.reflect.Modifier.isAbstract(primary.getModifiers())) {
            throw new ServiceException("@DatabusCmp 主类必须是可实例化的普通 class：" + primary.getName());
        }
        DatabusCmp cmp = AnnotationUtils.findAnnotation(primary, DatabusCmp.class);
        if (cmp == null) {
            throw new ServiceException("脚本主类缺少 @DatabusCmp 注解：" + primary.getName());
        }
        validateNodeBaseType(primary, cmp.nodeType());

        Class<?> cfgClass = resolveCfgClass(primary, cmp);
        List<PropSchema> fields = cfgClass == null ? List.of() : CfgIntrospector.introspect(cfgClass);
        if (cfgClass == null) {
            log.warn("[databus-script] 脚本件 {} 未解析到嵌套 Cfg，param_schema 将为空，表单退化为 JSON 编辑",
                primary.getName());
        }
        String schemaJson = JsonUtils.toJsonString(new ComponentSchemaBody(fields));
        String dataExample = cmp.dataExample() == null || cmp.dataExample().isBlank()
            ? null : cmp.dataExample();

        return new ScriptArtifact(primary, classLoader, cmp.code() == null ? "" : cmp.code(),
            cmp.nodeType(), dataExample, fields, schemaJson);
    }

    /**
     * 体检项：注解里声明的节点类型，必须和主类实际继承的父类对得上。
     *
     * <p>为什么 javac 帮不上这个忙：它只管语法和类型继承，不知道业务上
     * 「BOOLEAN 节点必须继承 NodeBooleanComponent」这层约定。注解说自己是布尔节点、
     * 类却继承了普通 NodeComponent，编译照样通过，一直拖到链路跑进 EL 的 IF 条件槽
     * 才炸 ClassCastException——离案发现场十万八千里，排查的人两头蒙。所以保存时就把
     * 普通/布尔/循环/迭代/分支五类一一对照，当场拦下，报错就落在写代码的人眼前。
     */
    private void validateNodeBaseType(Class<?> primary, NodeTypeKind nodeType) {
        Class<? extends NodeComponent> expected = switch (nodeType) {
            case NODE -> NodeComponent.class;
            case BOOLEAN -> NodeBooleanComponent.class;
            case FOR -> NodeForComponent.class;
            case ITERATOR -> NodeIteratorComponent.class;
            case SWITCH -> NodeSwitchComponent.class;
        };
        if (!expected.isAssignableFrom(primary)) {
            throw new ServiceException("@DatabusCmp(nodeType=" + nodeType.name()
                + ") 要求主类继承 " + expected.getName() + "，实际 " + primary.getName());
        }
    }

    /**
     * 找出主类的配置类 Cfg——决定参数表单长什么样的那个内嵌类。
     *
     * <p>三条线索按优先级试：注解里显式写了 cfg() 最明确，直接用；其次精确匹配
     * 「主类简名 + Cfg」（MyAtom 就找 MyAtomCfg）；再次退化找唯一一个以 Cfg 结尾的
     * 嵌套类（不想起标准名字时的懒人写法）。都落空、或者以 Cfg 结尾的有好几个
     * 分不清，就放弃解析，表单退化成纯 JSON 编辑——宁缺毋滥，猜错了类，
     * 表单就张冠李戴了。
     *
     * <p>硬规矩：嵌套 Cfg 必须是 static。非静态的内部类捏着一个外部实例的引用，
     * 配置反序列化时没法凭空造出这个外部实例，所以直接拒绝保存。
     */
    private Class<?> resolveCfgClass(Class<?> primary, DatabusCmp cmp) {
        if (cmp.cfg() != void.class) {
            return cmp.cfg();
        }
        String exactName = primary.getSimpleName() + "Cfg";
        Class<?> fallback = null;
        int fallbackCount = 0;
        for (Class<?> nested : primary.getDeclaredClasses()) {
            if (!nested.getSimpleName().endsWith("Cfg")) {
                continue;
            }
            if (!java.lang.reflect.Modifier.isStatic(nested.getModifiers())) {
                throw new ServiceException("嵌套 Cfg 必须声明为 static：" + nested.getName()
                    + "（非静态内部类无法被配置反序列化）");
            }
            if (nested.getSimpleName().equals(exactName)) {
                return nested;
            }
            fallback = nested;
            fallbackCount++;
        }
        if (fallbackCount == 1) {
            return fallback;
        }
        if (fallbackCount > 1) {
            log.warn("[databus-script] 主类 {} 存在多个以 Cfg 结尾的嵌套类且无 {}，跳过 Cfg 解析",
                primary.getName(), exactName);
        }
        return null;
    }

    /**
     * 从源码里抠出顶级 public 类名，拿去当「文件名」喂给 javac。
     *
     * <p>javac 有个老规矩：文件名必须和 public 类名一致，不然直接报错。源码是
     * 数据库里的字符串，本来没有文件名，所以按正则抓第一个「public class」——
     * 嵌套类的声明一定排在顶级类后面，抓到的必然是顶级类。抓不到就返回 null，
     * 上层用兜底文件名（编不过时错误信息里能对上号就行）。
     */
    private String detectPublicClassName(String source) {
        Matcher matcher = PUBLIC_CLASS_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 把 javac 吐回来的诊断攒成一句「人话」摘要：错误总数、前 5 处（带行列号）、
     * 剩下的只提示「见明细」——详情在 ScriptCompileException 的诊断列表里，
     * 编辑器回显定位靠它。
     *
     * <p>先过滤出 ERROR 是因为诊断里混着 WARNING/NOTE，只有 ERROR 才算编不过。
     * 万一一个 ERROR 都没有却失败了（javac 偶发不给具体诊断），就把编译器的
     * 原始输出兜底递上去。
     */
    private String summarizeErrors(List<ScriptDiagnostic> diagnostics, String compilerOut) {
        List<ScriptDiagnostic> errors = diagnostics.stream()
            .filter(d -> "ERROR".equals(d.kind()))
            .toList();
        if (errors.isEmpty()) {
            String tail = compilerOut == null ? "" : compilerOut.strip();
            return tail.isBlank() ? "编译失败（javac 未返回具体诊断）" : tail;
        }
        StringBuilder sb = new StringBuilder("编译失败，共 ").append(errors.size()).append(" 处错误：");
        int shown = Math.min(5, errors.size());
        for (int i = 0; i < shown; i++) {
            ScriptDiagnostic d = errors.get(i);
            sb.append('\n').append(i + 1).append(". ");
            if (d.line() > 0) {
                sb.append("第").append(d.line()).append("行").append(d.column() > 0 ? ":" + d.column() : "").append(' ');
            }
            sb.append(d.message());
        }
        if (errors.size() > shown) {
            sb.append("\n… 其余 ").append(errors.size() - shown).append(" 处见诊断明细");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // classpath 解析（IDE / fat jar）
    // ---------------------------------------------------------------------

    /**
     * 拼书架清单（classpath 字符串）。
     *
     * <p>清单在进程内基本不变——部署完依赖就定了，所以编第一次后缓存起来复用：
     * 字段用 volatile、更新走 synchronized 双检锁。保存操作可能并发进来，
     * 但清单解析（尤其 fat jar 那条路的解压）只该发生一次，后来者直接等着拿现成的。
     */
    private String buildClasspath() {
        List<String> cached = classpathCache;
        if (cached != null) {
            return String.join(File.pathSeparator, cached);
        }
        synchronized (this) {
            if (classpathCache == null) {
                classpathCache = resolveClasspath();
                log.info("[databus-script] 脚本编译 classpath 就绪，共 {} 项", classpathCache.size());
            }
            return String.join(File.pathSeparator, classpathCache);
        }
    }

    /**
     * 真正解析 javac 能用的书架清单，分两条路：先看自己是不是住在 fat jar 里
     * （locateFatJar() 找没找到根 jar）；住在就走解压缓存路子
     * （resolveFatJarClasspath）；散着跑（IDE / exploded 部署）就顺着类加载器链
     * 把 URL 落成磁盘路径，再兜底拼上系统属性 java.class.path，两头合并去重。
     *
     * <p>两条路都空手而归就直接报错——没有书架，javac 连平台自己的类都看不见，
     * 编什么都只会喊「找不到符号」。
     */
    private List<String> resolveClasspath() {
        File fatJar = locateFatJar();
        if (fatJar != null) {
            return resolveFatJarClasspath(fatJar);
        }
        // IDE / exploded：优先取类加载器链 URL，兜底 java.class.path
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        ClassLoader cl = JavaSourceCompiler.class.getClassLoader();
        while (cl instanceof URLClassLoader ucl) {
            for (URL url : ucl.getURLs()) {
                File file = urlToFile(url);
                if (file != null) {
                    entries.putIfAbsent(file.getAbsolutePath(), file.getAbsolutePath());
                }
            }
            cl = cl.getParent();
        }
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            for (String item : cp.split(File.pathSeparator)) {
                if (!item.isBlank()) {
                    entries.putIfAbsent(item, item);
                }
            }
        }
        if (entries.isEmpty()) {
            throw new ServiceException("无法解析脚本编译 classpath（类加载器与 java.class.path 均为空）");
        }
        return List.copyOf(entries.values());
    }

    /**
     * 看看自己住在哪：读本类的代码源地址（ProtectionDomain——JVM 记录
     * 「这个类从哪儿加载来」的地方）。
     *
     * <p>地址长成 {@code jar:file:/x/app.jar!/BOOT-INF/classes!/}（Boot ≤3.1）
     * 或 {@code jar:nested:/x/app.jar/!BOOT-INF/classes/!}（Boot 3.2+ 换了套娃
     * 格式），就说明住在 fat jar 里，顺手把大 jar 在磁盘上的真实路径从地址里
     * 剥出来返回；地址里既没有「!」也没有 .jar 字样，就是解压跑（典型如 IDE），
     * 返回 null 走散装路子。Windows 上路径可能带个盘符前导斜杠（/E:/x），
     * 也一并剥掉。
     */
    private File locateFatJar() {
        try {
            ProtectionDomain pd = JavaSourceCompiler.class.getProtectionDomain();
            CodeSource cs = pd == null ? null : pd.getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            String loc = cs.getLocation().toString().replace('\\', '/');
            int bang = loc.indexOf('!');
            int jarIdx = loc.toLowerCase().indexOf(".jar");
            if (bang < 0 || jarIdx < 0) {
                return null;
            }
            String head = loc.substring(0, jarIdx + 4)
                .replace("jar:", "")
                .replace("nested:", "")
                .replace("file:", "");
            // Windows 上 URI 可能形如 /E:/x/app.jar，剥掉盘符前导斜杠
            if (head.length() >= 3 && head.startsWith("/") && head.charAt(2) == ':') {
                head = head.substring(1);
            }
            File jar = new File(head);
            return jar.isFile() ? jar : null;
        } catch (Exception e) {
            log.warn("[databus-script] fat jar 定位失败，回退 java.class.path：{}", e.getMessage());
            return null;
        }
    }

    /**
     * fat jar 里的书架怎么摆：把套娃解开、摊平到临时目录，再拼清单。
     *
     * <p>缓存目录名 = jar 文件名 + 长度 + 最后修改时间，天然按部署包版本化——
     * 换了新包，尺寸或时间一变，自动落进新目录重新解，绝不会拿旧依赖冒充新的。
     * 解压完写一个 .ready 标记文件，之后编译看到标记在就整个跳过，
     * 一个进程只解一次。最终清单 = 解出来的 classes 目录 + lib 下的每个真 jar
     * 文件，排序只为稳定输出（日志和缓存诊断好对比）。
     */
    private List<String> resolveFatJarClasspath(File fatJar) {
        try {
            Path cacheDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "databus-script-cp",
                fatJar.getName() + "-" + fatJar.length() + "-" + fatJar.lastModified());
            Path ready = cacheDir.resolve(".ready");
            Path classesDir = cacheDir.resolve("classes");
            Path libDir = cacheDir.resolve("lib");
            if (!Files.exists(ready)) {
                log.info("[databus-script] 首次在 fat jar 内编译，解压 classpath 到 {}（仅一次）", cacheDir);
                Files.createDirectories(classesDir);
                Files.createDirectories(libDir);
                extractFatJar(fatJar, classesDir, libDir);
                Files.writeString(ready, fatJar.getAbsolutePath(), StandardCharsets.UTF_8);
            }
            List<String> entries = new ArrayList<>();
            entries.add(classesDir.toString());
            File[] libs = libDir.toFile().listFiles((dir, name) -> name.endsWith(".jar"));
            if (libs != null) {
                for (File lib : libs) {
                    entries.add(lib.getAbsolutePath());
                }
            }
            entries.sort(String::compareTo);
            return entries;
        } catch (Exception e) {
            throw new ServiceException("fat jar classpath 解压失败：" + e.getMessage(), e);
        }
    }

    /**
     * 动手解压：把大 jar 当普通 zip 打开，逐条往外拷。
     *
     * <p>BOOT-INF/classes/ 下的条目摊平进 classes 目录（那是我们自己的类，
     * 去掉那层目录前缀）；BOOT-INF/lib/ 下的每个内嵌 jar 拷成 lib 目录里独立的
     * jar 文件——套娃由此变回 javac 认识的普通文件。其余条目（启动器、META-INF）
     * 一概不碰。
     */
    private void extractFatJar(File fatJar, Path classesDir, Path libDir) throws IOException {
        try (ZipFile zip = new ZipFile(fatJar)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("BOOT-INF/classes/")) {
                    String rel = name.substring("BOOT-INF/classes/".length());
                    if (rel.isBlank()) {
                        continue;
                    }
                    copyEntry(zip, entry, classesDir.resolve(rel));
                } else if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                    copyEntry(zip, entry, libDir.resolve(name.substring(name.lastIndexOf('/') + 1)));
                }
            }
        }
    }

    /**
     * 拷一个 zip 条目到目标文件：父目录不存在就先建，同名直接覆盖
     * （重解压时旧文件不必先删）。
     */
    private void copyEntry(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 把 URL 落成磁盘文件。只有 file: 协议转得了；嵌套 jar 那种（jar:/nested:）
     * 磁盘上根本没有独立文件可指，转不了就返回 null 跳过——那部分依赖走上面的
     * fat jar 解压路径供给。
     */
    private File urlToFile(URL url) {
        try {
            if ("file".equals(url.getProtocol())) {
                return Paths.get(url.toURI()).toFile();
            }
        } catch (Exception ignored) {
            // 嵌套/非文件 URL 忽略
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // 内存编译 IO
    // ---------------------------------------------------------------------

    /**
     * 把源码字符串包装成 javac 认识的「文件」：真文件住磁盘，这个只住内存，
     * javac 来要内容的时候（getCharContent）把字符串递过去。
     *
     * <p>URI 是编的（string:///），平时不参与编译，只在报错信息里露个脸帮人
     * 对号；文件名必须跟 public 主类同名——javac 的老规矩。
     */
    private static class SourceFileObject extends SimpleJavaFileObject {

        private final String source;

        SourceFileObject(String fileName, String source) {
            super(URI.create("string:///" + fileName), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    /**
     * 编出来的字节码写去哪：同样是内存「文件」，背后一根字节数组输出流。
     *
     * <p>javac 编完一个类就来要笔（openOutputStream），把流递给它；写出来的
     * byte[] 稍后由 {@link MemoryJavaFileManager} 在收尾时统一收割。
     */
    private static class ClassFileObject extends SimpleJavaFileObject {

        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        ClassFileObject(String binaryName) {
            super(URI.create("byte:///" + binaryName.replace('.', '/') + Kind.CLASS.extension),
                Kind.CLASS);
        }

        byte[] getBytes() {
            return stream.toByteArray();
        }

        @Override
        public java.io.OutputStream openOutputStream() {
            return stream;
        }
    }

    /**
     * javac 的「文件管理员」替身：正事全让标准管理员去干，只拦一处——编译器想
     * 往磁盘写 .class 文件的时候（getJavaFileForOutput），换成内存流。
     *
     * <p>于是全部产物（主类、嵌套 Cfg、匿名类）都进 Map（binaryName → 字节码），
     * 一个临时文件都不产生——「纯内存编译」的下半截就在这。close/flush 时把各流
     * 攒下的字节统一收割进 classBytes；hasLocation 对 CLASS_PATH 开绿灯——让
     * javac 相信书架上确实有东西可查，不然它可能拒绝去书架找符号。
     */
    private static class MemoryJavaFileManager
        extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, byte[]> classBytes;
        private final Map<String, ClassFileObject> outputs = new TreeMap<>();

        MemoryJavaFileManager(StandardJavaFileManager fileManager, Map<String, byte[]> classBytes) {
            super(fileManager);
            this.classBytes = classBytes;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            ClassFileObject fileObject = new ClassFileObject(className);
            outputs.put(className, fileObject);
            return fileObject;
        }

        @Override
        public void close() throws IOException {
            for (Map.Entry<String, ClassFileObject> entry : outputs.entrySet()) {
                classBytes.put(entry.getKey(), entry.getValue().getBytes());
            }
            super.close();
        }

        @Override
        public void flush() throws IOException {
            for (Map.Entry<String, ClassFileObject> entry : outputs.entrySet()) {
                classBytes.put(entry.getKey(), entry.getValue().getBytes());
            }
            super.flush();
        }

        @Override
        public boolean hasLocation(Location location) {
            return StandardLocation.CLASS_PATH == location || super.hasLocation(location);
        }
    }
}
