package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 数据总线连接管理对象 sys_databus_connection。
 *
 * <p>三层物料模型第二层（Connection 实例）的落库实体。2026-09-18 按设计文档 §16 重构为
 * "通用元数据列 + 配置 JSON 列"形态：
 * <ol>
 *   <li>通用列：connectionId / connectionName / connectorType / enabled + 审计列，所有 connector 共用</li>
 *   <li>{@link #config}：非敏感参数的明文 JSON（字段 schema 由 connector 的 describe() 声明），
 *       如 bpmHttp 的 endpoint / accessKey / timeoutMs / retryCount</li>
 *   <li>{@link #credentials}：敏感参数 JSON（如 apiSecret / token / secretKey），
 *       经 MyBatis 字段级加密落库（依赖 mybatis-encryptor.enable=true，算法/密钥走全局 yml 配置）</li>
 * </ol>
 *
 * <p>未来新增 connector 类型只需在 config/credentials 内扩展 JSON 字段，本表零 DDL。
 * 平铺 BO/VO（endpoint/username/password…）与本表之间的组装拆解在 Service 层完成，
 * 是 1D-P0 前端写死表单阶段的过渡形态，动态表单阶段 BO 也会改为直接收 config JSON。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_databus_connection")
public class SysDatabusConnection extends BaseEntity {

    /**
     * 主键 id（雪花 ID）
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 连接 ID（全局唯一，组件层引用此值；非主键）
     */
    private String connectionId;

    /**
     * 连接名称（用户可读）
     */
    private String connectionName;

    /**
     * Connector 类型标识（如 "bpmHttp"）
     */
    private String connectorType;

    /**
     * 非敏感连接配置（明文 JSON）。
     * <p>key 与 connector descriptor 的 configSchema 对齐，
     * bpmHttp 含 endpoint / accessKey / timeoutMs / retryCount。
     */
    private String config;

    /**
     * 敏感凭据（JSON 字符串，MyBatis 拦截器写入时加密、读取时解密）。
     * <p>裸 {@link EncryptField} 走 application.yml 的 mybatis-encryptor 全局算法与密钥；
     * 全局开关关闭时该列退化为明文 JSON（仅允许本地开发）。
     * bpmHttp 当前含 authPassword。
     */
    @EncryptField
    private String credentials;

    /**
     * 是否启用（Y启用 N禁用；启用时执行链路自动注入 DatabusContext）
     */
    private String enabled;

    /**
     * 备注（BaseEntity 不含 remark，表列自持）
     */
    private String remark;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}
