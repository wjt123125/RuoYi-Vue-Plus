package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安装/血缘记录对象 databus_install
 * <p>
 * 「owner→source 引用记录」（设计正本 §十）：一行＝某用户安装了某市场物料一次。
 * 同构承载：收藏（星标引用）与安装未改在这一层同构，产品语义分开。
 * <p>
 * local_resource_id 指向本地资源（链路 fork 副本主键 / 组件共享注册时直接指向源组件 id）；
 * uninstall 仅对 FORK 记录删本地副本，SHARED 只删安装记录；
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
     * 本地资源 id（链路 fork 副本主键 / SHARED 时直接指向源组件 id）
     */
    private Long localResourceId;

    /**
     * 安装模式（FORK 链路快照落地 / SHARED 组件共享注册不落副本 / FAVORITE 星标）
     */
    private String mode;

    /**
     * 目录归属（组件 SHARED 安装的引用挂树组织视角，§十二-7：null=未归组）
     * <p>
     * 仅组织视角，与副本所有权解耦：挪动引用叶子＝更新本列，不动源组件行的 directory_id。
     */
    private Long directoryId;

    /**
     * 落快照时的逻辑源码指纹（md5）
     */
    private String contentHash;

    /**
     * 安装时间（DB 默认 current_timestamp，应用侧显式写入）
     */
    private LocalDateTime createTime;

}