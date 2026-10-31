package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorDescriptor;
import org.dromara.databus.connector.ConnectorException;
import org.dromara.databus.connector.ConnectorRegistry;
import org.dromara.databus.context.JsonCodec;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.SysDatabusConnection;
import org.dromara.databus.domain.bo.SysDatabusConnectionBo;
import org.dromara.databus.domain.vo.ConnectionOptionVo;
import org.dromara.databus.domain.vo.SysDatabusConnectionVo;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.SysDatabusConnectionMapper;
import org.dromara.databus.service.ISysDatabusConnectionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据总线连接管理 Service 实现。
 *
 * <p>核心职责：
 * <ul>
 *   <li>CRUD sys_databus_connection 表（通用列 + config 明文 JSON + credentials 加密 JSON，见设计文档 §16）</li>
 *   <li>{@link #testConnection(SysDatabusConnectionBo)} 调 Connector 实测连通性</li>
 *   <li>{@link #loadEnabledConnections()} 把启用的 DB 记录转运行时 {@link Connection} 列表（两份 JSON 合并为全量 config Map）</li>
 * </ul>
 *
 * <p>配置一律走 {@code bo.config} 这一个 Map：前端按 {@code describe().configSchema} 渲染动态表单，
 * 提交什么 key 就存什么 key，本类不认识任何具体连接器的字段名。剩下两件事同样由 descriptor 声明驱动、
 * 不硬编码字段名：哪些 key 落加密列看 {@code ConfigField.sensitive}，哪些 key 必填看
 * {@code ConfigField.required}。因此新增一种连接器（如 jdbc-postgresql）不需要改本类一行——
 * 这是「连接器零内置」（方案 §1.2）在连接管理层的那一半：连接器实现不在平台里，
 * 平台也不能替它认字段。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SysDatabusConnectionServiceImpl implements ISysDatabusConnectionService {

    private static final String ENABLED_YES = "Y";

    private final SysDatabusConnectionMapper connectionMapper;

    private final ConnectorRegistry connectorRegistry;

    private final DatabusChainMapper chainMapper;

    @Override
    public SysDatabusConnectionVo queryById(Long id) {
        SysDatabusConnection entity = connectionMapper.selectById(id);
        return entity == null ? null : entityToVo(entity);
    }

    @Override
    public List<ConnectionOptionVo> queryOptions() {
        List<SysDatabusConnection> entities = connectionMapper.selectList(
            Wrappers.<SysDatabusConnection>lambdaQuery()
                .eq(SysDatabusConnection::getEnabled, ENABLED_YES)
                .orderByAsc(SysDatabusConnection::getId));
        // 同一 type 的连接可能有多行（多套库），族是 type 的静态属性，缓存一次避免重复 describe()
        Map<String, String> familyCache = new LinkedHashMap<>();
        return entities.stream()
            .map(e -> {
                String family = familyCache.computeIfAbsent(e.getConnectorType(), this::resolveFamily);
                return new ConnectionOptionVo(e.getId(), e.getConnectionId(), e.getConnectionName(),
                    e.getConnectorType(), family.isEmpty() ? null : family);
            })
            .toList();
    }

    /**
     * 取连接器的能力族（{@code ConnectorDescriptor.family}），供编辑器按族过滤连接下拉。
     *
     * <p>用 {@link ConnectorRegistry#get(String)} 而非 {@code ofType()}：后者在连接器
     * 未启用 / 已禁用 / 未安装时抛 {@link ConnectorException}，而本方法服务于**列表渲染**——
     * 连接行指向的连接器包可能已被卸载，此时列表仍要照常出来（族填 null，前端退化为不过滤），
     * 不能因为一个包装不上就整个下拉 500。describe() 亦兜底吞异常，理由同上。
     *
     * <p>{@code computeIfAbsent} 不允许映射函数返回 null，故本方法返回空串代表「取不到」，
     * 由调用方在构造 VO 前转回 null（避免 JSON 里出现 connectorFamily="" 让前端误判为有族）。
     */
    private String resolveFamily(String connectorType) {
        Connector connector = connectorRegistry.get(connectorType);
        if (connector == null) {
            return "";
        }
        try {
            String family = connector.describe().getFamily();
            return family == null ? "" : family;
        } catch (RuntimeException ex) {
            log.warn("[databus] 取连接器 {} 的能力族失败，连接下拉退化为不过滤: {}", connectorType, ex.getMessage());
            return "";
        }
    }

    @Override
    public PageResult<SysDatabusConnectionVo> queryPageList(SysDatabusConnectionBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<SysDatabusConnection> lqw = buildQueryWrapper(bo);
        lqw.orderByDesc(SysDatabusConnection::getUpdateTime)
            .orderByDesc(SysDatabusConnection::getId);
        Page<SysDatabusConnection> page = connectionMapper.selectPage(pageQuery.build(), lqw);
        List<SysDatabusConnectionVo> voList = page.getRecords().stream()
            .map(this::entityToVo)
            .toList();
        return PageResult.build(voList, page.getTotal());
    }

    @Override
    public Boolean insertByBo(SysDatabusConnectionBo bo) {
        validateConnectionIdUnique(bo);
        validateConnectorTypeExists(bo.getConnectorType());
        validateRequiredSensitive(bo.getConnectorType(), normalizeConfig(bo));
        SysDatabusConnection add = buildEntityFromBo(bo);
        add.setId(null);
        boolean flag = connectionMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(SysDatabusConnectionBo bo) {
        SysDatabusConnection existing = connectionMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("连接不存在或已删除");
        }
        validateConnectionIdUnique(bo);
        validateConnectorTypeExists(bo.getConnectorType());
        // 敏感字段留空表示不修改：按 descriptor 的 sensitive 声明从库中旧值补齐，避免空值覆盖
        mergeMissingSensitive(normalizeConfig(bo), existing, bo.getConnectorType());
        SysDatabusConnection update = buildEntityFromBo(bo);
        return connectionMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        validateNotReferenced(ids);
        return connectionMapper.deleteByIds(ids) > 0;
    }

    @Override
    public Object testConnection(SysDatabusConnectionBo bo) {
        if (StringUtils.isBlank(bo.getConnectorType())) {
            throw new ServiceException("Connector 类型不能为空");
        }
        // 编辑场景敏感字段留空表示沿用原值：从库中取回再实测，否则测的是空密码
        if (bo.getId() != null) {
            SysDatabusConnection existing = connectionMapper.selectById(bo.getId());
            if (existing == null) {
                throw new ServiceException("连接不存在或已删除");
            }
            mergeMissingSensitive(normalizeConfig(bo), existing, bo.getConnectorType());
        }
        Connector connector = connectorRegistry.ofType(bo.getConnectorType());
        Connection runtimeConn = boToRuntimeConnection(bo);
        try {
            return connector.testConnection(runtimeConn);
        } catch (ConnectorException e) {
            // 测试连接失败转业务异常，R.fail 返回前端展示 msg
            log.warn("测试连接失败 connectionId={} code={} msg={}",
                bo.getConnectionId(), e.getCode(), e.getMessage());
            throw new ServiceException("测试连接失败: " + e.getMessage());
        }
    }

    @Override
    public List<Connection> loadEnabledConnections() {
        LambdaQueryWrapper<SysDatabusConnection> lqw = Wrappers.<SysDatabusConnection>lambdaQuery()
            .eq(SysDatabusConnection::getEnabled, ENABLED_YES);
        List<SysDatabusConnection> entities = connectionMapper.selectList(lqw);
        List<Connection> connections = new ArrayList<>(entities.size());
        for (SysDatabusConnection entity : entities) {
            try {
                connections.add(toRuntimeConnection(entity));
            } catch (Exception e) {
                // 单条转换失败不阻断执行，记录后跳过；后续组件取该 connectionId 会抛"未注册"
                log.error("连接转换失败 id={} connectionId={} err={}",
                    entity.getId(), entity.getConnectionId(), e.getMessage());
            }
        }
        return connections;
    }

    // ---------------------------- 查询与校验 ----------------------------

    private LambdaQueryWrapper<SysDatabusConnection> buildQueryWrapper(SysDatabusConnectionBo bo) {
        LambdaQueryWrapper<SysDatabusConnection> lqw = Wrappers.lambdaQuery();
        if (bo != null) {
            lqw.like(StringUtils.isNotBlank(bo.getConnectionId()),
                SysDatabusConnection::getConnectionId, bo.getConnectionId());
            lqw.like(StringUtils.isNotBlank(bo.getConnectionName()),
                SysDatabusConnection::getConnectionName, bo.getConnectionName());
            lqw.eq(StringUtils.isNotBlank(bo.getConnectorType()),
                SysDatabusConnection::getConnectorType, bo.getConnectorType());
            lqw.eq(StringUtils.isNotBlank(bo.getEnabled()),
                SysDatabusConnection::getEnabled, bo.getEnabled());
        }
        return lqw;
    }

    /**
     * 校验 connectionId 在未删除记录中唯一。
     */
    private void validateConnectionIdUnique(SysDatabusConnectionBo bo) {
        Long currentId = bo.getId() == null ? -1L : bo.getId();
        Long count = connectionMapper.selectCount(Wrappers.<SysDatabusConnection>lambdaQuery()
            .eq(SysDatabusConnection::getConnectionId, bo.getConnectionId())
            .ne(SysDatabusConnection::getId, currentId));
        if (count != null && count > 0) {
            throw new ServiceException("连接ID '" + bo.getConnectionId() + "' 已存在");
        }
    }

    /**
     * 校验 connectorType 在 ConnectorRegistry 中已注册（避免保存无法使用的连接）。
     */
    private void validateConnectorTypeExists(String connectorType) {
        try {
            connectorRegistry.ofType(connectorType);
        } catch (ConnectorException e) {
            throw new ServiceException("Connector 类型 '" + connectorType + "' 未注册: " + e.getMessage());
        }
    }

    /**
     * 删除前校验：所选连接未被任何未删除链路的组件引用。
     * <p>在 cmp_property JSON 文本中匹配完整片段 {@code "connectionId":"<值>"}，
     * 完整片段避免短 connectionId 子串误匹配；命中则拦截并列出引用链路。
     */
    private void validateNotReferenced(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<SysDatabusConnection> connections = connectionMapper.selectByIds(ids);
        List<String> connectionIds = connections.stream()
            .map(SysDatabusConnection::getConnectionId)
            .filter(StringUtils::isNotBlank)
            .toList();
        if (connectionIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<DatabusChain> wrapper = Wrappers.<DatabusChain>lambdaQuery()
            .select(DatabusChain::getId, DatabusChain::getChainName, DatabusChain::getChainCode)
            .and(orGroup -> {
                for (int i = 0; i < connectionIds.size(); i++) {
                    if (i > 0) {
                        orGroup.or();
                    }
                    orGroup.like(DatabusChain::getCmpProperty, referenceLikePattern(connectionIds.get(i)));
                }
            });
        List<DatabusChain> referenced = chainMapper.selectList(wrapper);
        if (!referenced.isEmpty()) {
            String names = referenced.stream()
                .map(chain -> chain.getChainName() + "(" + chain.getChainCode() + ")")
                .collect(Collectors.joining("、"));
            throw new ServiceException("所选连接仍被链路引用，无法删除：" + names
                + "；请先在对应链路中移除或更换连接");
        }
    }

    /** cmp_property 中的精确引用片段（已转义 LIKE 通配符），外层由 MP 包裹 % 匹配 */
    private String referenceLikePattern(String connectionId) {
        return "\"connectionId\":\"" + escapeLike(connectionId) + "\"";
    }

    /** 转义 MySQL LIKE 通配符（MySQL 默认转义符为反斜杠，无需显式 ESCAPE 子句） */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    // ---------------------------- config Map ↔ config/credentials JSON ----------------------------

    /**
     * BO → 实体（通用列直映；config Map 按 descriptor 的 sensitive 标记
     * 拆成 config（明文）与 credentials（加密）两份 JSON）。
     */
    private SysDatabusConnection buildEntityFromBo(SysDatabusConnectionBo bo) {
        SysDatabusConnection entity = new SysDatabusConnection();
        entity.setId(bo.getId());
        entity.setConnectionId(bo.getConnectionId());
        entity.setConnectionName(bo.getConnectionName());
        entity.setConnectorType(bo.getConnectorType());
        entity.setEnabled(StringUtils.isBlank(bo.getEnabled()) ? ENABLED_YES : bo.getEnabled());
        entity.setRemark(bo.getRemark());

        Map<String, Object> runtimeConfig = normalizeConfig(bo);
        Set<String> sensitiveKeys = sensitiveKeys(bo.getConnectorType());
        Map<String, Object> plainConfig = new LinkedHashMap<>();
        Map<String, Object> credentialConfig = new LinkedHashMap<>();
        runtimeConfig.forEach((key, value) -> {
            if (sensitiveKeys.contains(key)) {
                credentialConfig.put(key, value);
            } else {
                plainConfig.put(key, value);
            }
        });
        entity.setConfig(JsonCodec.toJson(plainConfig));
        entity.setCredentials(JsonCodec.toJson(credentialConfig));
        return entity;
    }

    /**
     * 从 connector descriptor 取敏感字段名集合（ConfigField.sensitive=true）。
     */
    private Set<String> sensitiveKeys(String connectorType) {
        ConnectorDescriptor descriptor = connectorRegistry.ofType(connectorType).describe();
        return descriptor.getConfigSchema().entrySet().stream()
            .filter(entry -> entry.getValue().isSensitive())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    /**
     * 把 BO 的 config 归一化成可写 Map 并写回 BO：null → 空 Map，非 null → 拷贝一份。
     * <p>拷贝而不是直接用：反序列化出来的 Map 实现不保证可变，后面 {@link #mergeMissingSensitive}
     * 要往里 put。写回 BO 是为了让 {@link #buildEntityFromBo} 拿到补齐后的同一份。
     */
    private Map<String, Object> normalizeConfig(SysDatabusConnectionBo bo) {
        Map<String, Object> config = bo.getConfig() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(bo.getConfig());
        bo.setConfig(config);
        return config;
    }

    /**
     * 新增时校验必填的敏感字段：descriptor 里 {@code sensitive && required} 的 key 必须给了值。
     * <p>编辑时由 {@link #mergeMissingSensitive} 兜（留空即沿用旧值），新增没有旧值可沿用，
     * 空着存进去只会得到一条永远连不上的连接。原来这里是硬编码的「apiSecret 不能为空」，
     * 现在字段名与必填性都来自连接器自己的 configSchema（方案 §十一 阶段 B 第 7 步）。
     * <p>非敏感的必填项不在这里拦：连接器执行期的校验能给出更精确的错因
     * （如 JDBC 的「host/port/database 与自定义 URL 二选一」），前端也已按同一份 schema 做提交前校验。
     */
    private void validateRequiredSensitive(String connectorType, Map<String, Object> config) {
        connectorRegistry.ofType(connectorType).describe().getConfigSchema().forEach((key, field) -> {
            if (field.isRequired() && field.isSensitive() && isBlankValue(config.get(key))) {
                String label = StringUtils.isBlank(field.getLabel()) ? key : field.getLabel();
                throw new ServiceException(label + " 不能为空");
            }
        });
    }

    /**
     * 编辑时敏感字段留空沿用旧值。
     * <p>对 descriptor 标记的每个 sensitive key，新 config 未提供或为空时，
     * 从旧 credentials（已由 MyBatis 解密）取回；旧值也空则拦，避免敏感字段被覆盖丢失。
     */
    private void mergeMissingSensitive(Map<String, Object> config, SysDatabusConnection existing, String connectorType) {
        Set<String> sensitiveKeys = sensitiveKeys(connectorType);
        if (sensitiveKeys.isEmpty()) {
            return;
        }
        Map<String, Object> oldCredentials = parseJsonMap(existing.getCredentials());
        for (String key : sensitiveKeys) {
            if (isBlankValue(config.get(key))) {
                Object oldValue = oldCredentials.get(key);
                if (isBlankValue(oldValue)) {
                    throw new ServiceException("原连接未配置敏感字段 " + key + "，请填写后保存");
                }
                config.put(key, oldValue);
            }
        }
    }

    /** 值判空：null 或空字符串视为未提供（编辑器空串/缺省统一按「留空」处理）。 */
    private boolean isBlankValue(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    /**
     * BO → 运行时连接（testConnection 用，未落库也能测试；无需拆敏感字段）。
     */
    private Connection boToRuntimeConnection(SysDatabusConnectionBo bo) {
        Connection conn = new Connection();
        conn.setId(bo.getConnectionId());
        conn.setName(bo.getConnectionName());
        conn.setConnectorType(bo.getConnectorType());
        conn.setConfig(normalizeConfig(bo));
        return conn;
    }

    /**
     * 实体 → 运行时连接（执行链路注入用）：config 与 credentials 解密后合并为全量 Map。
     */
    private Connection toRuntimeConnection(SysDatabusConnection entity) {
        Connection conn = new Connection();
        conn.setId(entity.getConnectionId());
        conn.setName(entity.getConnectionName());
        conn.setConnectorType(entity.getConnectorType());
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(parseJsonMap(entity.getConfig()));
        // credentials 经 MyBatis 拦截器已解密；同名 key 以凭据为准（正常不会重名）
        merged.putAll(parseJsonMap(entity.getCredentials()));
        conn.setConfig(merged);
        return conn;
    }

    /**
     * 实体 → VO（列表/详情/编辑回显）。
     * <p>安全口径：只回显 config（明文列），credentials 整列不下发——列表与详情都拿不到明文密钥。
     * 前端敏感输入框常态空白，留空即表示沿用原值（update/test 两条路径都由
     * {@link #mergeMissingSensitive} 从库中补齐），填新值才替换。
     */
    private SysDatabusConnectionVo entityToVo(SysDatabusConnection entity) {
        SysDatabusConnectionVo vo = new SysDatabusConnectionVo();
        vo.setId(entity.getId());
        vo.setConnectionId(entity.getConnectionId());
        vo.setConnectionName(entity.getConnectionName());
        vo.setConnectorType(entity.getConnectorType());
        vo.setEnabled(entity.getEnabled());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());

        Map<String, Object> config = parseJsonMap(entity.getConfig());
        vo.setConfig(config.isEmpty() ? null : config);
        return vo;
    }

    /**
     * 解析 JSON 对象字符串为 Map；null/空/非对象 JSON 一律返回空 Map，不抛异常。
     */
    private Map<String, Object> parseJsonMap(String json) {
        if (StringUtils.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        Object parsed = JsonCodec.parse(json);
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>(map.size());
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        log.warn("连接配置 JSON 不是对象，按空配置处理 raw={}", json);
        return new LinkedHashMap<>();
    }

}
