package org.dromara.databus.mapper;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusComponentConnectorDep;

import java.util.Collection;
import java.util.List;

/**
 * 组件↔连接器依赖关系 Mapper（material-market §15.12.3）。
 *
 * <p>只服务两件事：组件保存时**按 code 先删后插**重写依赖行（§15.12.7），
 * 以及停用/卸载连接器时**按族反查受影响的组件编码**（§15.12.5）。
 * 后者走 {@code idx_connector_family}，是本表存在的全部意义——
 * 若不建表，同样的反查要把全库 {@code param_schema} 读出来逐个解析 JSON。
 *
 * <p>无对应 Vo：本表是派生索引，不直接对前端暴露，故第二个泛型参数复用实体
 * （与 {@code SysUserRoleMapper} 同写法），不调用任何 selectVo* 方法。
 *
 * @author databus
 */
public interface DatabusComponentConnectorDepMapper
    extends BaseMapperPlus<DatabusComponentConnectorDep, DatabusComponentConnectorDep> {

    /**
     * 按组件编码批量删依赖行（组件硬删、以及同步前的「先删」都走这里）。
     *
     * <p>软退役（{@code deprecated=1}）**不得**调用本方法：老链还在跑、还在用那个连接器，
     * 反查影响面必须算上它（§15.12.7 第 4 点）。
     */
    default int deleteByCodes(Collection<String> componentCodes) {
        if (CollUtil.isEmpty(componentCodes)) {
            return 0;
        }
        return delete(Wrappers.<DatabusComponentConnectorDep>lambdaQuery()
            .in(DatabusComponentConnectorDep::getComponentCode, componentCodes));
    }

    /**
     * 按能力族反查依赖它的组件编码列表（停用/卸载连接器的影响面提示，§15.12.5）。
     *
     * <p>入参是**族**（jdbc / bpmHttp），不是具体 type（jdbc-postgresql）。调用方手上通常只有
     * type，需先经 {@code databus_connector_def.manifest_snapshot} 取
     * {@code ConnectorDescriptor.family} 再查——直接拿 type 来查会恒返空集（静默错）。
     */
    default List<String> selectCodesByFamily(String connectorFamily) {
        // 只 select 一列，避免把整行拖回来；不用 BaseMapperPlus.selectObjs(wrapper, mapper)——
        // 它与 BaseMapper.selectObjs(wrapper, ResultHandler) 对方法引用构成重载歧义，编译不过
        return selectList(Wrappers.<DatabusComponentConnectorDep>lambdaQuery()
                .select(DatabusComponentConnectorDep::getComponentCode)
                .eq(DatabusComponentConnectorDep::getConnectorFamily, connectorFamily))
            .stream()
            .map(DatabusComponentConnectorDep::getComponentCode)
            .toList();
    }

}
