package org.dromara.databus.connector.plugin;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * dev 装载目录的配置解析与路径安全校验（从 DevConnectorLoader 拆出）。
 *
 * <p>职责边界：只把 {@code databus.connector.dev-plugin-dir} 的配置值变成可用目录，
 * 不读 manifest、不装载、不写台账。
 *
 * <p>安全边界（§5.3 第 3 条）：路径转 {@code getCanonicalPath()} 后必须是项目根
 * （{@code user.dir}）的下级路径；含 {@code ..}、UNC、跨盘符一律拒绝且直接抛错，
 * 让配置错误在启动时暴露，而不是装载时表现为「目录不存在」。
 *
 * @author databus
 */
@Component
@Profile("dev")
public class DevPluginDirResolver {

    /**
     * dev 装载目录（逗号分隔多个 {@code target/classes}）；留空即不装载任何连接器，
     * 启动对账会把台账中的 DEV 行全部删掉
     */
    @Value("${databus.connector.dev-plugin-dir:}")
    private String devPluginDir;

    /**
     * 配置值切目录列表（逗号分隔，忽略空白项）。
     */
    public List<String> configuredDirs() {
        List<String> dirs = new ArrayList<>();
        if (devPluginDir == null || devPluginDir.isBlank()) {
            return dirs;
        }
        for (String item : devPluginDir.split(",")) {
            if (!item.isBlank()) {
                dirs.add(item.trim());
            }
        }
        return dirs;
    }

    /**
     * 配置目录的可读名（给人看的报错用）：从 {@code .../databus-connector-jdbc-pg/target/classes}
     * 里取 {@code target} 的上一级，即 maven 模块名；取不到 target 就退化成末段目录名。
     */
    public String configuredDirNames() {
        List<String> names = new ArrayList<>();
        for (String dir : configuredDirs()) {
            String[] segs = dir.trim().replace('\\', '/').split("/");
            String name = segs[segs.length - 1];
            for (int i = 1; i < segs.length - 1; i++) {
                if ("target".equals(segs[i])) {
                    name = segs[i - 1];
                    break;
                }
            }
            names.add(name);
        }
        return names.isEmpty() ? "（未配置）" : String.join("、", names);
    }

    /**
     * 路径安全校验（§5.3 第 3 条）：拒绝 {@code ..}、UNC、跨盘符，
     * canonical 后必须是项目根（{@code user.dir}）的下级路径。
     */
    public Path resolveSafeDir(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.contains("..")) {
            throw new ServiceException("dev 装载目录不允许含 ..：" + text);
        }
        String canonical;
        String rootCanonical;
        try {
            canonical = Paths.get(text).toAbsolutePath().toFile().getCanonicalPath();
            rootCanonical = new File(System.getProperty("user.dir")).getCanonicalPath();
        } catch (IOException | RuntimeException e) {
            throw new ServiceException("dev 装载目录路径解析失败（" + text + "）：" + e.getMessage());
        }
        if (canonical.startsWith("\\\\")) {
            throw new ServiceException("dev 装载目录不允许 UNC 路径：" + text);
        }
        if (!canonical.startsWith(rootCanonical + File.separator)) {
            throw new ServiceException("dev 装载目录必须是项目根的下级路径（§5.3 路径限制）：目录 "
                + canonical + "，项目根 " + rootCanonical);
        }
        return new File(canonical).toPath();
    }
}
