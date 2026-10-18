package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安装/血缘记录对象 databus_install
 * <p>
 * 「owner→source 引用记录」（设计正本 §十）：一行＝某用户 fork 了某市场物料一次。
 * 同构承载：收藏（星标引用）与 fork 未改在这一层同构，产品语义分开。
 * <p>
 * local_resource_id 指向本地副本（链路主键 / 组件 DB 行 id），uninstall 据此删副本；
 * content_hash 记录落快照时的逻辑源码指纹（未改快照与原件同 hash → 复用源 bean）。
 *
 * @author databus
 */
@Data
@TableName("databus_install")
public class DatabusInstall {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 安装者 id（owner）
     */
    private Long ownerId;

    /**
     * 市场物料 id（source）
     */
    private Long marketItemId;

    /**
     * 本地副本 id（链路主键 / 组件 DB 行 id）
     */
    private Long localResourceId;

    /**
     * 安装模式（FORK / FAVORITE）
     */
    private String mode;

    /**
     * 落快照时的逻辑源码指纹（md5）
     */
    private String contentHash;

    /**
     * 安装时间（DB 默认 current_timestamp，应用侧显式写入）
     */
    private LocalDateTime createTime;

}