package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 脚本组件版本历史 databus_component_version（append-only 审计）。
 *
 * <p>每保存一版脚本追加一行；回滚不复制状态、只产生一条内容等同目标旧版的新版本行
 * （remark 记录来源版本）。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_component_version")
public class DatabusComponentVersion extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 组件主键
     */
    private Long componentId;

    /**
     * 版本号（组件内自增，1 起）
     */
    private Integer versionNo;

    /**
     * 脚本语言（java/groovy 等，一期 java）
     */
    private String scriptLang;

    /**
     * 该版本脚本正文
     */
    private String scriptBody;

    /**
     * 版本备注（如：回滚自版本 v3）
     */
    private String remark;

}
