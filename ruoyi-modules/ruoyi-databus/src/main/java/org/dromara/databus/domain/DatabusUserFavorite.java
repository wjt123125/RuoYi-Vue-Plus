package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户收藏对象 databus_user_favorite
 * <p>
 * 工作区统一资源树的个人化组织层（JetBrains Favorites 范式，目录单真相之外的唯一个人维度）：
 * 极简三业务列（user_id / resource_type / resource_id）+ create_time，无审计字段、无逻辑删除
 * （表结构先例 sys_user_role）。resource_type 取值 chain / component。
 * 同时也是 owner→source 引用记录，PaaS 化 fork 表的同构基座（见 workbench-unification.md §3.1）。
 *
 * @author databus
 */
@Data
@TableName("databus_user_favorite")
public class DatabusUserFavorite {

    /**
     * 主键id（雪花）
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 资源类型（chain / component）
     */
    private String resourceType;

    /**
     * 资源id
     */
    private Long resourceId;

    /**
     * 收藏时间（DB 默认 current_timestamp，应用侧显式写入）
     */
    private LocalDateTime createTime;

}
