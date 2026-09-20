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
import org.dromara.databus.domain.SysDatabusConnection;
import org.dromara.databus.domain.bo.SysDatabusConnectionBo;
import org.dromara.databus.domain.vo.SysDatabusConnectionVo;
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
 * <p>1D-P0 过渡形态：前端写死 BPM 平铺表单，BO/VO 仍是平铺字段
 * （endpoint/accessKey/apiSecret/timeout/retryCount），本类集中负责
 * 平铺字段 ↔ 运行时 config Map（key 与 {@link org.dromara.databus.connector.bpm.BpmHttpConnectionCfg}
 * 及 descriptor configSchema 对齐）↔ config/credentials 两份 JSON 的组装拆解。
 * 哪些 key 进加密列由 connector descriptor 的 {@code ConfigField.sensitive} 声明驱动，
 * 本类不硬编码"密码字段名"。第二种 connector 落地、前端动态表单化后，平铺映射随之移除。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SysDatabusConnectionServiceImpl implements ISysDatabusConnectionService {

    private static final String ENABLED_YES = "Y";

    /** 运行时 Connection.config 的 key（与 BpmHttpConnectionCfg / BpmHttpConnector.describe() 一致）。 */
    private static final String CFG_ENDPOINT = "endpoint";
    private static final String CFG_ACCESS_KEY = "accessKey";
    private static final String CFG_API_SECRET = "apiSecret";
    private static final String CFG_TIMEOUT_MS = "timeoutMs";
    private static final String CFG_RETRY_COUNT = "retryCount";

    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_RETRY_COUNT = 0;

    private final SysDatabusConnectionMapper connectionMapper;

    private final ConnectorRegistry connectorRegistry;

    @Override
    public SysDatabusConnectionVo queryById(Long id) {
        SysDatabusConnection entity = connectionMapper.selectById(id);
        return entity == null ? null : entityToVo(entity);
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
        SysDatabusConnection update = buildEntityFromBo(bo);
        return connectionMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        return connectionMapper.deleteByIds(ids) > 0;
    }

    @Override
    public String testConnection(SysDatabusConnectionBo bo) {
        if (StringUtils.isBlank(bo.getConnectorType())) {
            throw new ServiceException("Connector 类型不能为空");
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

    // ---------------------------- 平铺 BO ↔ config/credentials JSON ----------------------------

    /**
     * 平铺 BO → 实体（通用列直映；配置部分先组装运行时全量 Map，
     * 再按 descriptor 的 sensitive 标记拆成 config（明文）与 credentials（加密）两份 JSON）。
     */
    private SysDatabusConnection buildEntityFromBo(SysDatabusConnectionBo bo) {
        SysDatabusConnection entity = new SysDatabusConnection();
        entity.setId(bo.getId());
        entity.setConnectionId(bo.getConnectionId());
        entity.setConnectionName(bo.getConnectionName());
        entity.setConnectorType(bo.getConnectorType());
        entity.setEnabled(StringUtils.isBlank(bo.getEnabled()) ? ENABLED_YES : bo.getEnabled());
        entity.setRemark(bo.getRemark());

        Map<String, Object> runtimeConfig = boToRuntimeMap(bo);
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
     * 平铺 BO → 运行时全量 config Map（BpmHttpConnectionCfg schema）。
     * <p>1D-P0 唯一的 BPM 专属映射点（平铺表单是过渡形态）；默认值在此兜底。
     */
    private Map<String, Object> boToRuntimeMap(SysDatabusConnectionBo bo) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CFG_ENDPOINT, bo.getEndpoint());
        config.put(CFG_ACCESS_KEY, bo.getAccessKey());
        config.put(CFG_API_SECRET, bo.getApiSecret());
        config.put(CFG_TIMEOUT_MS, bo.getTimeout() == null ? DEFAULT_TIMEOUT_MS : bo.getTimeout());
        config.put(CFG_RETRY_COUNT, bo.getRetryCount() == null ? DEFAULT_RETRY_COUNT : bo.getRetryCount());
        return config;
    }

    /**
     * BO → 运行时连接（testConnection 用，未落库也能测试；无需拆敏感字段）。
     */
    private Connection boToRuntimeConnection(SysDatabusConnectionBo bo) {
        Connection conn = new Connection();
        conn.setId(bo.getConnectionId());
        conn.setName(bo.getConnectionName());
        conn.setConnectorType(bo.getConnectorType());
        conn.setConfig(boToRuntimeMap(bo));
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
     * 实体 → 平铺 VO（列表/详情/编辑回显）。
     * <p>1D-P0 密码随详情/列表解密回填（与步骤 7 平铺模型行为一致，前端写死表单零改动）；
     * 动态表单阶段再改为"密码留空表示不修改"。
     */
    private SysDatabusConnectionVo entityToVo(SysDatabusConnection entity) {
        Map<String, Object> config = parseJsonMap(entity.getConfig());
        Map<String, Object> credentials = parseJsonMap(entity.getCredentials());

        SysDatabusConnectionVo vo = new SysDatabusConnectionVo();
        vo.setId(entity.getId());
        vo.setConnectionId(entity.getConnectionId());
        vo.setConnectionName(entity.getConnectionName());
        vo.setConnectorType(entity.getConnectorType());
        vo.setEnabled(entity.getEnabled());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());

        vo.setEndpoint(asString(config.get(CFG_ENDPOINT)));
        vo.setAccessKey(asString(config.get(CFG_ACCESS_KEY)));
        vo.setApiSecret(asString(credentials.get(CFG_API_SECRET)));
        vo.setTimeout(asInteger(config.get(CFG_TIMEOUT_MS), DEFAULT_TIMEOUT_MS));
        vo.setRetryCount(asInteger(config.get(CFG_RETRY_COUNT), DEFAULT_RETRY_COUNT));
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

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("连接配置数值字段格式非法，使用默认值 value={} default={}", value, defaultValue);
            return defaultValue;
        }
    }

}
