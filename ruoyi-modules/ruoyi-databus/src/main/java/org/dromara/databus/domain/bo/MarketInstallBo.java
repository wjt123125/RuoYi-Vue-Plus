package org.dromara.databus.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 安装（fork）业务对象：目标目录
 * <p>
 * directoryId 为 null/0 均归一为「未归组」；ownerId 由后端从登录态取。
 *
 * @author databus
 */
@Data
public class MarketInstallBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标目录id（null=未归组）
     */
    private Long directoryId;

}