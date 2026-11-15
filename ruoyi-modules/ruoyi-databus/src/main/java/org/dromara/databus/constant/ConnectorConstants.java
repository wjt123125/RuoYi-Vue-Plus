package org.dromara.databus.constant;

/**
 * 连接器与插件域常量：引擎类型、来源渠道、插件包结构与清单、健康状态、包体上限。
 *
 * @author databus
 */
public final class ConnectorConstants {

    private ConnectorConstants() {
    }

    /**
     * 运行引擎：JVM 进程内
     */
    public static final String ENGINE_JVM = "jvm";

    /**
     * 开发态来源（本地开发目录热加载）
     */
    public static final String SOURCE_DEV = "DEV";

    /**
     * 上传来源
     */
    public static final String SOURCE_UPLOAD = "UPLOAD";

    /**
     * 管理端安装来源
     */
    public static final String SOURCE_ADMIN = "ADMIN";

    /**
     * 插件清单文件名
     */
    public static final String MANIFEST_ENTRY = "manifest.json";

    /**
     * 插件依赖目录
     */
    public static final String LIB_DIR = "lib";

    /**
     * 插件包内依赖目录前缀
     */
    public static final String LIB_PREFIX = "lib/";

    /**
     * JDBC 驱动 SPI 资源路径
     */
    public static final String DRIVER_SPI_RESOURCE = "META-INF/services/java.sql.Driver";

    /**
     * 插件包体大小上限（50MB）
     */
    public static final long MAX_PACKAGE_BYTES = 50L * 1024 * 1024;

    /**
     * 健康状态：正常
     */
    public static final String HEALTH_OK = "OK";

    /**
     * 健康状态：缺驱动
     */
    public static final String HEALTH_MISSING_DRIVER = "MISSING_DRIVER";

    /**
     * 健康状态：未加载
     */
    public static final String HEALTH_NOT_LOADED = "NOT_LOADED";

    /**
     * 市场审核权限点（连接器发布绕过市场审核时需要校验）
     */
    public static final String PERM_MARKET_REVIEW = "databus:market:review";
}
