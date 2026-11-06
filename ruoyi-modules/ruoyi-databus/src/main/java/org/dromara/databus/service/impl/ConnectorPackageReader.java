package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.connector.ConnectorManifest;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 连接器包读取与审核预览（从 DatabusConnectorServiceImpl 拆出的只读支撑类）。
 *
 * <p>职责边界：按 type+version 定位落盘 zip、读包字节、生成「包内清单 + 可疑 API」预览。
 * 上传落盘、启停、卸载仍在 Service；本类不写任何状态，只在读不到包时抛错。
 *
 * <p>审核口径（§2.3 / §九）：只对含 mainClass 的实现 jar 做常量池扫描
 * （官方件实现类与 common 随包合打一个 jar，§2.4）；驱动等第三方 jar 只列体积 / class 数 /
 * MANIFEST 快照，不扫描（避免 JDBC 驱动自带 Socket 刷屏）。
 *
 * @author databus
 */
@Component
@RequiredArgsConstructor
public class ConnectorPackageReader {

    /**
     * 包内清单文件名（zip 根级，必选）
     */
    private static final String MANIFEST_ENTRY = "manifest.json";

    /**
     * 来源：dev 目录装载（没有 zip 实体，见 {@link #rejectDevRow}）
     */
    private static final String SOURCE_DEV = "DEV";

    /**
     * 包内可疑 API 描述符（内部名，点→斜杠）：读写文件 / 外连 / 反射 / 起进程 / 开线程。
     * 仅用于审核预览，非硬门禁。
     */
    private static final Set<String> SUSPICIOUS_CONSTANTS = Set.of(
        // 读写文件 / IO
        "java/io/File", "java/io/FileInputStream", "java/io/FileOutputStream", "java/io/RandomAccessFile",
        "java/io/FileReader", "java/io/FileWriter", "java/io/PrintWriter", "java/io/BufferedReader",
        "java/io/InputStream", "java/io/OutputStream",
        "java/nio/file/Files", "java/nio/file/Paths", "java/nio/file/Path",
        // 外连网络
        "java/net/Socket", "java/net/ServerSocket", "java/net/URL", "java/net/URLConnection",
        "java/net/HttpURLConnection", "java/net/DatagramSocket", "java/net/DatagramPacket",
        "java/net/InetSocketAddress", "java/net/InetAddress",
        // 反射
        "java/lang/reflect/Method", "java/lang/reflect/Constructor", "java/lang/reflect/Field",
        "java/lang/reflect/Proxy", "java/lang/reflect/AccessibleObject",
        // 起进程
        "java/lang/ProcessBuilder", "java/lang/Process", "java/lang/Runtime",
        // 开线程
        "java/lang/Thread", "java/lang/Runnable", "java/util/concurrent/Executors",
        "java/util/concurrent/ThreadPoolExecutor", "java/util/concurrent/ExecutorService",
        // 自定义类加载
        "java/lang/ClassLoader"
    );

    private final DatabusConnectorDefMapper defMapper;

    /**
     * manifest 解析用 Jackson3 mapper（容器 bean）
     */
    private final ObjectMapper objectMapper;

    /**
     * 下载原包（审核「看得见」§2.3 第 1 条）：按 type+version 定位 zip 并返回字节。
     */
    public byte[] readPackage(String type, String version) {
        DatabusConnectorDef def = requireDefByTypeVersion(type, version);
        rejectDevRow(def, "没有原包可下载（它跑的是编译输出目录里的 class，运行期没有 zip）");
        try {
            return Files.readAllBytes(packageFileOf(def));
        } catch (IOException e) {
            throw new ServiceException("读取连接器包失败（" + type + ":" + version + "）：" + e.getMessage());
        }
    }

    /**
     * 包文件名（{@code {artifact}-{version}.zip}），下载附件名与落盘/构建产物同一规则。
     */
    public String packageFileName(String type, String version) {
        DatabusConnectorDef def = requireDefByTypeVersion(type, version);
        return parseManifest(def.getManifestSnapshot()).packageFileName();
    }

    /**
     * 包内清单 + 关键实现类反编译预览（审核「看得见」§2.3 第 2、3 条）。
     */
    public Map<String, Object> inspectPackage(String type, String version) {
        DatabusConnectorDef def = requireDefByTypeVersion(type, version);
        rejectDevRow(def, "没有原包可查看（它跑的是编译输出目录里的 class，运行期没有 zip）");
        ConnectorManifest manifest = parseManifest(def.getManifestSnapshot());
        String mainClass = manifest.getMainClass();
        String mainClassEntry = mainClass == null ? null : mainClass.replace('.', '/') + ".class";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("version", version);
        result.put("engine", manifest.getEngine());
        result.put("mainClass", mainClass);

        List<Map<String, Object>> files = new ArrayList<>();
        Set<String> suspicious = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(packageFileOf(def)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] bytes = zis.readAllBytes();
                if (MANIFEST_ENTRY.equals(name)) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", name);
                    f.put("size", bytes.length);
                    f.put("kind", "manifest");
                    files.add(f);
                } else if (name.endsWith(".jar")) {
                    files.add(inspectJar(name, bytes, mainClassEntry, suspicious));
                } else {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", name);
                    f.put("size", bytes.length);
                    f.put("kind", "other");
                    files.add(f);
                }
            }
        } catch (IOException e) {
            throw new ServiceException("解析连接器包失败（" + type + ":" + version + "）：" + e.getMessage());
        }
        result.put("files", files);
        result.put("suspicious", new ArrayList<>(suspicious));
        return result;
    }

    /**
     * 按 type+version 取 def 行（下载 / 清单共用前置校验）。
     */
    private DatabusConnectorDef requireDefByTypeVersion(String type, String version) {
        DatabusConnectorDef def = defMapper.selectOne(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(DatabusConnectorDef::getType, type)
            .eq(DatabusConnectorDef::getVersion, version)
            .last("limit 1"));
        if (def == null) {
            throw new ServiceException("连接器不存在：" + type + ":" + version);
        }
        return def;
    }

    /**
     * def 行 → 落盘 zip 文件路径（plugins/{type}/{version}/{artifact}-{version}.zip）。
     * 文件名从 manifestSnapshot 推导，保证与构建产物、上传落盘同一规则。
     */
    private Path packageFileOf(DatabusConnectorDef def) {
        ConnectorManifest manifest = parseManifest(def.getManifestSnapshot());
        Path zip = Paths.get(def.getPackagePath(), manifest.packageFileName());
        if (!Files.isRegularFile(zip)) {
            throw new ServiceException("连接器包文件缺失：" + zip);
        }
        return zip;
    }

    /**
     * 解析单个 jar：体积 / 是否含 mainClass / class 数 / MANIFEST（截断）；
     * 命中实现 jar 时对其 class 做可疑常量池扫描，结果并入 suspicious。
     */
    private Map<String, Object> inspectJar(String jarName, byte[] jarBytes, String mainClassEntry, Set<String> suspicious) {
        Map<String, Object> jar = new LinkedHashMap<>();
        jar.put("name", jarName);
        jar.put("size", jarBytes.length);
        jar.put("kind", "jar");
        boolean mainJar = mainClassEntry != null && containsEntry(jarBytes, mainClassEntry);
        jar.put("mainJar", mainJar);
        List<String> classes = new ArrayList<>();
        String manifestText = null;
        try (ZipInputStream jis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry je;
            while ((je = jis.getNextEntry()) != null) {
                String n = je.getName();
                if ("META-INF/MANIFEST.MF".equals(n)) {
                    manifestText = truncate(new String(jis.readAllBytes(), StandardCharsets.UTF_8), 2048);
                } else if (n.endsWith(".class")) {
                    classes.add(n);
                    if (mainJar) {
                        for (String c : readUtf8Constants(jis.readAllBytes())) {
                            if (SUSPICIOUS_CONSTANTS.contains(c)) {
                                suspicious.add(c);
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 损坏 jar 不阻断清单：name / 体积仍可展示
        }
        jar.put("classes", classes.size());
        if (mainJar) {
            jar.put("classList", classes);
        }
        if (manifestText != null) {
            jar.put("manifest", manifestText);
        }
        return jar;
    }

    /**
     * jar 内是否含指定 entry。
     */
    private static boolean containsEntry(byte[] jarBytes, String entryName) {
        try (ZipInputStream jis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry je;
            while ((je = jis.getNextEntry()) != null) {
                if (entryName.equals(je.getName())) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // 忽略
        }
        return false;
    }

    /**
     * 读 class 文件常量池的 UTF-8 字符串（可疑 API 扫描用，不做完整字节码解析）。
     */
    private static List<String> readUtf8Constants(byte[] classBytes) {
        List<String> out = new ArrayList<>();
        if (classBytes == null || classBytes.length < 10) {
            return out;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (in.readInt() != 0xCAFEBABE) {
                return out;
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> { // Utf8
                        int len = in.readUnsignedShort();
                        out.add(new String(in.readNBytes(len), StandardCharsets.UTF_8));
                    }
                    case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 15 -> in.skipNBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); i++; } // long/double 占两个槽
                    default -> { return out; } // 未知 tag，中止
                }
            }
        } catch (IOException ignored) {
            // 畸形 class 返回已解析部分
        }
        return out;
    }

    /**
     * 长文本截断（MANIFEST 只给关键段，防超大刷屏）。
     */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n…（已截断，剩余 " + (s.length() - max) + " 字符）";
    }

    /**
     * dev 目录装载的行没有 zip 这个实体（它跑的是编译输出目录里的 class），按包读取一律拒绝。
     * <p>与 Service 的 {@code rejectDevRow} 同一口径；这里单存一份是为了避免支撑类反向依赖 Service
     * 形成循环，文案改动时两处需同步。
     */
    private void rejectDevRow(DatabusConnectorDef def, String reason) {
        if (SOURCE_DEV.equals(def.getSource())) {
            throw new ServiceException("连接器 " + def.getType() + " 是 dev 目录装载的，" + reason
                + "；改代码后调「重新加载」即可生效。需要走审核、启停、下载包的完整流程，请 mvn package 后在「上架物料」里选择连接器并上传该 zip");
        }
    }

    /**
     * manifest JSON → 解析模型（解析失败抛给调用方）。
     */
    private ConnectorManifest parseManifest(String manifestJson) {
        try {
            return objectMapper.readValue(manifestJson, ConnectorManifest.class);
        } catch (JacksonException e) {
            throw new ServiceException("manifest.json 解析失败：" + e.getOriginalMessage());
        }
    }
}
