package org.dromara.databus.service;

import org.dromara.databus.domain.bo.DatabusChainDirectoryBo;
import org.dromara.databus.domain.vo.DatabusChainDirectoryVo;

import java.util.Collection;
import java.util.List;

/**
 * 链路目录 Service 接口（链路工作台资源树组织层）
 *
 * @author databus
 */
public interface IDatabusChainDirectoryService {

    /**
     * 查询全部目录（平表返回，前端组树；按 sort 升序 id 升序）
     */
    List<DatabusChainDirectoryVo> queryList();

    /**
     * 按主键查询目录
     */
    DatabusChainDirectoryVo queryById(Long id);

    /**
     * 新增目录（校验父目录存在 + 同级名称唯一）
     */
    Boolean insertByBo(DatabusChainDirectoryBo bo);

    /**
     * 修改目录（重命名/排序/拖拽换父，换父做自指与环校验）
     */
    Boolean updateByBo(DatabusChainDirectoryBo bo);

    /**
     * 批量删除目录（子目录非空拦截 + 挂链拦截，不做级联删除）
     */
    Boolean deleteByIds(Collection<Long> ids);

    /**
     * 移动链路目录归属（工作台树拖拽：directoryId 空=移出到未归组）
     */
    Boolean moveChain(Long chainId, Long directoryId);

}
