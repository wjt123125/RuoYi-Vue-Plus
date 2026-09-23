package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 链路管理页顶部统计块视图对象（按 status 分组计数）。
 * <p>
 * 分页 list 无法前端聚合准确计数，故后端补此轻量统计接口。
 * 第四格「运行异常」待执行记录表落地后点亮，当前前端置灰占位。
 *
 * @author databus
 */
@Data
public class ChainStatsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 总链路数（草稿 + 已发布 + 已下线，delFlag 自动过滤）
     */
    private Long total;

    /**
     * 待发布草稿数（status=0）
     */
    private Long draft;

    /**
     * 运行中已发布数（status=1）
     */
    private Long published;

    /**
     * 已下线数（status=2）
     */
    private Long offline;

}
