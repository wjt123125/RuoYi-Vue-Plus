package org.dromara.databus.service.impl;

import com.yomahub.liteflow.publisher.PublishChainRequest;
import com.yomahub.liteflow.publisher.PublishScriptRequest;
import com.yomahub.liteflow.publisher.RemoveRuleRequest;
import com.yomahub.liteflow.publisher.RulePublisher;
import com.yomahub.liteflow.publisher.RulePublisherFactory;
import com.yomahub.liteflow.repository.sql.SqlPublisherConfig;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.executor.DatabusExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Rule-DB 规则发布服务（链路发布/下线动作的底层推送）。
 * <p>
 * 通过 LiteFlow v2.16.1 统一发布 API {@link RulePublisher} 把链路 EL（与脚本节点）
 * 推送到 lf_chain / lf_script 表，作为执行期权威源；执行侧由 rule-db starter 自动
 * 回源加载（首次执行回源编译，Caffeine 缓存命中后热路径零远程调用）。
 * <p>
 * 发布侧复用容器 {@link DataSource}（{@link SqlPublisherConfig.Builder#dataSource}），
 * applicationName 取 {@code spring.application.name}，与执行侧
 * {@code liteflow.rule-db.application-name}（yml 引用同一占位符）天然一致——
 * 两侧不一致时发布成功但应用永远看不到规则（Rule-DB 典型踩坑）。
 * <p>
 * {@link RulePublisher} 实现了 AutoCloseable，按操作 try-with-resources 创建/关闭。
 * 表名 {@code lf_script} 为 Rule-DB 默认 table-prefix（未自定义，硬编码与配置一致）。
 *
 * @author databus
 */
@Slf4j
@Component
public class RulePublishService {

    /**
     * 查询 lf_script 存量脚本指纹，用于跨链路脚本冲突校验。
     * content_md5 = MD5(script_data) 小写 hex（Rule-DB 对账判据，见 rule-db.md §11）。
     */
    private static final String SELECT_SCRIPT_MD5 =
        "SELECT content_md5 FROM lf_script WHERE application_name = ? AND node_id = ?";

    private final DataSource dataSource;

    private final JdbcTemplate jdbcTemplate;

    private final String applicationName;

    private final DatabusExecutor databusExecutor;

    public RulePublishService(DataSource dataSource,
                              @Value("${spring.application.name}") String applicationName,
                              DatabusExecutor databusExecutor) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.applicationName = applicationName;
        this.databusExecutor = databusExecutor;
    }

    /**
     * 发布链路到 Rule-DB：先推脚本节点（依赖顺序：先脚本后 chain，见 rule-db.md §5），
     * 再 UPSERT chain EL。
     * <p>
     * 发布顺序约定：本方法成功后调用方才更新 databus_chain.status——推 Rule-DB 失败时
     * 状态保持草稿/下线，不会出现"状态已发布但引擎无规则"的不可执行窗口；反过来推成功
     * 而状态更新失败会留孤儿规则，重发时被覆盖，无害。
     *
     * @param chainId       链路编码（lf_chain.chain_id）
     * @param el            LiteFlow EL 表达式
     * @param cmpProperty   画布逻辑组件树（提取脚本节点用，可为 null 表示无脚本）
     */
    public void publishChain(String chainId, String el, CmpProperty cmpProperty) {
        List<DatabusExecutor.ScriptNodeSpec> scripts = databusExecutor.collectScriptNodes(cmpProperty);
        try (RulePublisher publisher = createPublisher()) {
            for (DatabusExecutor.ScriptNodeSpec spec : scripts) {
                publishScriptIfCompatible(publisher, spec);
            }
            // chain 本体 UPSERT（不传 expectedVersion）：以 databus_chain 当前 EL 为准覆盖推送；
            // databus_chain 自身的并发发布由 status 流转的 DB 行更新兜底，lf_chain 版本单调递增无副作用
            publisher.publishChain(PublishChainRequest.builder()
                .chainId(chainId)
                .el(el)
                .build());
            log.info("[databus] 链路已发布到 Rule-DB chainId={}, scripts={}, applicationName={}",
                chainId, scripts.size(), applicationName);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("链路发布推送 Rule-DB 失败：" + e.getMessage());
        }
    }

    /**
     * 从 Rule-DB 移除链路（下线动作）。
     * <p>
     * 只删 chain 不删脚本：lf_script 主键 (application_name, node_id) 不区分链路，
     * 同名 tag 可能被其他链路引用，误删会连带破坏在用链路；孤儿脚本无 chain 引用不会
     * 被执行，重新发布同名 tag 时按指纹幂等覆盖。
     * <p>
     * 移除失败不抛出（调用方在状态落库后调用，规则残留无害且重发覆盖）。
     *
     * @param chainId 链路编码
     */
    public void removeChainQuietly(String chainId) {
        try (RulePublisher publisher = createPublisher()) {
            publisher.removeChain(RemoveRuleRequest.builder().targetId(chainId).build());
            log.info("[databus] 链路已从 Rule-DB 移除 chainId={}", chainId);
        } catch (Exception e) {
            log.error("[databus] Rule-DB 移除链路失败（残留规则无害，重新发布时覆盖）chainId={}: {}",
                chainId, e.getMessage(), e);
        }
    }

    private RulePublisher createPublisher() {
        return RulePublisherFactory.create(SqlPublisherConfig.builder()
            .applicationName(applicationName)
            .dataSource(dataSource)
            .build());
    }

    /**
     * 发布单个脚本，做跨链路冲突校验：lf_script 的 node_id 全局唯一（不分链路），
     * 而脚本 nodeId = 画布数据空间名（如 script1），跨链路同名 tag 几乎必然——
     * 若存量脚本与本链路脚本文本不同，静默覆盖会污染在用链路，故显式报错引导用户改名。
     * <p>
     * 指纹一致时跳过（幂等，避免无谓的 version+1 触发全集群重编译）。
     */
    private void publishScriptIfCompatible(RulePublisher publisher, DatabusExecutor.ScriptNodeSpec spec) {
        String nodeMd5 = DigestUtils.md5DigestAsHex(spec.script().getBytes(StandardCharsets.UTF_8));
        List<String> existing = jdbcTemplate.queryForList(SELECT_SCRIPT_MD5, String.class,
            applicationName, spec.nodeId());
        if (!existing.isEmpty() && !nodeMd5.equals(existing.get(0))) {
            throw new ServiceException("脚本标识'" + spec.nodeId()
                + "'已被其他链路占用（同标识脚本文本不同）。请在画布中修改该脚本组件的数据空间名后重试");
        }
        if (!existing.isEmpty()) {
            log.debug("[databus] 脚本指纹一致，跳过发布 nodeId={}", spec.nodeId());
            return;
        }
        publisher.publishScript(PublishScriptRequest.builder()
            .nodeId(spec.nodeId())
            .type(spec.type())
            .language(spec.language())
            .script(spec.script())
            .build());
        log.info("[databus] 脚本已发布到 Rule-DB nodeId={} type={} language={}",
            spec.nodeId(), spec.type(), spec.language());
    }

}
