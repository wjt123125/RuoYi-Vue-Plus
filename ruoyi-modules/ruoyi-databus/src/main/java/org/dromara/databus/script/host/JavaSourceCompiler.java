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
 * javax.pro 脚本编译器：把一段完整 Java 源码（组件主类 + 静态嵌套 Cfg）在内存中编译、
 * 用全新 {@link ScriptClassLoader} 加载，并反射 @DatabusCmp/@DatabusProp 物化契约。
 *
 * <p>编译 classpath 双模解析：
 * <ul>
 *   <li>IDE /  exploded：{@code java.class.path}（各模块 target/classes + m2 依赖）；</li>
 *   <li>Spring Boot fat jar：代码源位于 {@code BOOT-INF/classes}（{@code jar:file:..!/} 或
 *       Boot 3.2+ 的 {@code jar:nested:..!}），首次编译时把 BOOT-INF/classes 与
 *       BOOT-INF/lib 解压到临时缓存目录（按 jar 名+长度+修改时间版本化），
 *       以解压目录与 jar 文件拼 classpath（javac 无法读取嵌套条目）。</li>
 * </ul>
 *
 * <p>保留注解处理（不传 -proc:none）：搬迁期 Cfg 可继续使用 Lombok。
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
     * 编译一段脚本源码并物化契约。
     *
     * @param source 完整 Java 源码
     * @return 脚本工件（主类/加载器/契约）
     * @throws ScriptCompileException 编译失败（带行列诊断）
     * @throws ServiceException       结构校验失败（非编译期错误，如缺 @DatabusCmp、父类不符）
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
     * 校验注解声明的节点类型与主类实际父类型一致（避免 BOOLEAN 声明配 NodeComponent 父类，
     * 运行期才在条件槽炸出 ClassCastException）。
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
     * Cfg 解析：显式 cfg() 优先；否则找主类内 public static 嵌套类，
     * 先精确匹配 {@code <主类简名>Cfg}，再退化为唯一以 Cfg 结尾的嵌套类。
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

    private String detectPublicClassName(String source) {
        Matcher matcher = PUBLIC_CLASS_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

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
     * 从本类代码源判断是否运行在 fat jar 内，并定位磁盘上的根 jar 文件。
     * 兼容 Boot ≤3.1（{@code jar:file:/x/app.jar!/BOOT-INF/classes!/}）
     * 与 Boot 3.2+（{@code jar:nested:/x/app.jar/!BOOT-INF/classes/!}）。
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

    private void copyEntry(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

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
     * 源码内存文件（URI 仅用于诊断展示，文件名与 public 主类同名以满足 javac 校验）。
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
     * 字节码输出内存文件。
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
     * 把编译器全部输出重定向到内存 Map（binaryName → 字节码），不落临时文件。
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
