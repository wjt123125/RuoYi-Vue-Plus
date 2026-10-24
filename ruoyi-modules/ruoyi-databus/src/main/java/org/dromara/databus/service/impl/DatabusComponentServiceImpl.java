package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.component.schema.enums.EditorKind;
import org.dromara.databus.component.schema.model.CmpSchema;
import org.dromara.databus.component.schema.registry.ComponentSchemaRegistry;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusComponentDomain;
import org.dromara.databus.domain.DatabusComponentGroup;
import org.dromara.databus.domain.DatabusComponentVersion;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.bo.ScriptRollbackBo;
import org.dromara.databus.domain.bo.ScriptSaveBo;
import org.dromara.databus.domain.vo.ComponentDomainVo;
import org.dromara.databus.domain.vo.ComponentGroupVo;
import org.dromara.databus.domain.vo.ComponentOptionVo;
import org.dromara.databus.domain.vo.ComponentOptionsVo;
import org.dromara.databus.domain.vo.ComponentSchemaBody;
import org.dromara.databus.domain.vo.DatabusComponentVersionVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.domain.vo.ScriptRuntimeVo;
import org.dromara.databus.domain.vo.ScriptSaveResultVo;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentDomainMapper;
import org.dromara.databus.mapper.DatabusComponentGroupMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusComponentVersionMapper;
import org.dromara.databus.script.host.JavaSourceCompiler;
import org.dromara.databus.script.host.ScriptArtifact;
import org.dromara.databus.script.host.ScriptComponentRegistrar;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 组件元信息 Service 实现
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DatabusComponentServiceImpl implements IDatabusComponentService {

    /**
     * 启用状态
     */
    private static final String STATUS_ENABLED = "0";

    /**
     * 正常（未废弃）
     */
    private static final String DEPRECATED_NO = "0";

    /**
     * 已废弃（软退役，§十二-5）：删除被链路引用的组件时不物理删，置 1 停止物料面板投放，
     * 存量链路按 code 引用照常运行
     */
    private static final String DEPRECATED_YES = "1";

    /**
     * 软退役默认备注（原作者删除被引用组件时写入）
     */
    private static final String DEPRECATE_NOTE_RETIRED = "原作者已删除该组件，存量链路仍可运行，请尽快替换";

    /**
     * 一期脚本语言固定 java（javax.tools 完整类源码）
     */
    private static final String SCRIPT_LANG_JAVA = "java";

    /**
     * 字典表 char(1) 标记的肯定值（databus_component_domain.is_default）
     */
    private static final String FLAG_YES = "Y";

    private final DatabusComponentMapper componentMapper;

    private final DatabusComponentGroupMapper componentGroupMapper;

    private final DatabusComponentDomainMapper componentDomainMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentVersionMapper componentVersionMapper;

    private final ComponentSchemaRegistry componentSchemaRegistry;

    private final JavaSourceCompiler scriptCompiler;

    private final ScriptComponentRegistrar scriptRegistrar;

    @Override
    public DatabusComponentVo queryById(Long id) {
        return componentMapper.selectVoById(id);
    }

    @Override
    public PageResult<DatabusComponentVo> queryPageList(DatabusComponentBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<DatabusComponent> lqw = buildQueryWrapper(bo);
        Page<DatabusComponentVo> result = componentMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public ComponentOptionsVo queryOptions() {
        // 同码合流新秩序（2026-10-06 脚本宿主立法反转）：
        // ①同码启用且 script_body 非空＝库存脚本件，全量取 DB（含同码覆盖内置 jar 件）；
        // ②同码启用无 body＝治理覆盖行，治理取 DB、契约取内置注解（OVERLAY）；
        // ③无 DB 行＝纯内置件（SYSTEM）。「编码冲突丢弃 DB」策略废除。
        Map<String, CmpSchema> builtin = componentSchemaRegistry.all().stream()
            .collect(Collectors.toMap(CmpSchema::code, schema -> schema,
                (a, b) -> a, LinkedHashMap::new));

        List<DatabusComponent> rows = componentMapper.selectList(Wrappers.<DatabusComponent>lambdaQuery()
            .eq(DatabusComponent::getStatus, STATUS_ENABLED)
            // 软退役件（deprecated=1）停止投放：存量链路照常运行，新画布拖不进，树侧据此红标缺依赖
            .eq(DatabusComponent::getDeprecated, DEPRECATED_NO)
            .orderByAsc(DatabusComponent::getSort)
            .orderByAsc(DatabusComponent::getId));

        Set<String> dbOwnedCodes = new HashSet<>();
        List<ComponentOptionVo> dbOptions = new ArrayList<>();
        for (DatabusComponent row : rows) {
            dbOwnedCodes.add(row.getComponentCode());
            CmpSchema sysSchema = builtin.get(row.getComponentCode());
            if (StringUtils.isBlank(row.getScriptBody()) && sysSchema != null) {
                dbOptions.add(ComponentOptionVo.ofOverlay(sysSchema, row));
            } else {
                dbOptions.add(ComponentOptionVo.ofCustom(row, parseSchemaBody(row.getParamSchema(),
                    row.getComponentCode(), row.getId())));
            }
        }

        List<ComponentOptionVo> options = new ArrayList<>();
        builtin.forEach((code, schema) -> {
            if (!dbOwnedCodes.contains(code)) {
                options.add(ComponentOptionVo.ofSystem(schema));
            }
        });
        options.addAll(dbOptions);
        return ComponentOptionsVo.of(options);
    }

    @Override
    public List<ComponentGroupVo> queryGroups() {
        // 手动装配而非 selectVoList：VO 字段名（key/label）与实体属性名（groupKey/groupName）
        // 不一致，mapstruct-plus 的属性名映射拷不上
        return componentGroupMapper.selectList(Wrappers.<DatabusComponentGroup>lambdaQuery()
                .orderByAsc(DatabusComponentGroup::getSort)
                .orderByAsc(DatabusComponentGroup::getId))
            .stream()
            .map(row -> new ComponentGroupVo(row.getGroupKey(), row.getGroupName(), row.getColor(), row.getSort()))
            .toList();
    }

    @Override
    public List<ComponentDomainVo> queryDomains() {
        // 同上手动装配；is_default 的 char(1) Y/N 在此收敛为 Boolean
        return componentDomainMapper.selectList(Wrappers.<DatabusComponentDomain>lambdaQuery()
                .orderByAsc(DatabusComponentDomain::getSort)
                .orderByAsc(DatabusComponentDomain::getId))
            .stream()
            .map(row -> new ComponentDomainVo(row.getDomainKey(), row.getDomainName(), row.getColor(),
                row.getSort(), FLAG_YES.equals(row.getIsDefault())))
            .toList();
    }

    /**
     * 解析自定义行 param_schema 契约缓存列（新形态只装 schema 体 <code>{"fields":[...]}</code>）；
     * 空或解析失败返回 null，调用方降级为无 schema 的选项（前端回退 JSON 编辑器，不阻断 /options）。
     */
    private ComponentSchemaBody parseSchemaBody(String paramSchema, String code, Long id) {
        if (StringUtils.isBlank(paramSchema)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(paramSchema, ComponentSchemaBody.class);
        } catch (Exception e) {
            log.warn("[databus-schema] 自定义物料 param_schema 解析失败，降级无 schema code={}, id={}, err={}",
                code, id, e.getMessage());
            return null;
        }
    }

    private LambdaQueryWrapper<DatabusComponent> buildQueryWrapper(DatabusComponentBo bo) {
        LambdaQueryWrapper<DatabusComponent> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getComponentCode()), DatabusComponent::getComponentCode, bo.getComponentCode());
        lqw.like(StringUtils.isNotBlank(bo.getComponentName()), DatabusComponent::getComponentName, bo.getComponentName());
        lqw.eq(StringUtils.isNotBlank(bo.getCategory()), DatabusComponent::getCategory, bo.getCategory());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), DatabusComponent::getStatus, bo.getStatus());
        lqw.orderByAsc(DatabusComponent::getCategory)
            .orderByDesc(DatabusComponent::getCreateTime);
        return lqw;
    }

    @Override
    public Boolean insertByBo(DatabusComponentBo bo) {
        validateComponentCodeUnique(bo);
        DatabusComponent add = MapstructUtils.convert(bo, DatabusComponent.class);
        if (StringUtils.isBlank(add.getStatus())) {
            add.setStatus(STATUS_ENABLED);
        }
        if (StringUtils.isBlank(add.getDeprecated())) {
            add.setDeprecated(DEPRECATED_NO);
        }
        boolean flag = componentMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(DatabusComponentBo bo) {
        validateComponentCodeUnique(bo);
        DatabusComponent update = MapstructUtils.convert(bo, DatabusComponent.class);
        protectScriptContractColumns(bo.getId(), update);
        return componentMapper.updateById(update) > 0;
    }

    /**
     * 库存脚本件（script_body 非空）的契约四列以脚本编译物化结果为正本，
     * 治理编辑（databus:component:edit）不得覆盖；仅脚本保存管线可写。
     * MP updateById 默认 NOT_NULL 策略，置 null 即跳过这些列。
     */
    private void protectScriptContractColumns(Long id, DatabusComponent update) {
        if (id == null) {
            return;
        }
        DatabusComponent existing = componentMapper.selectById(id);
        if (existing != null && StringUtils.isNotBlank(existing.getScriptBody())) {
            update.setNodeType(null);
            update.setEditor(null);
            update.setParamSchema(null);
            update.setDataExample(null);
        }
    }

    /**
     * 校验组件编码在未删除记录中唯一
     */
    private void validateComponentCodeUnique(DatabusComponentBo bo) {
        Long currentId = bo.getId() == null ? -1L : bo.getId();
        Long count = componentMapper.selectCount(Wrappers.<DatabusComponent>lambdaQuery()
            .eq(DatabusComponent::getComponentCode, bo.getComponentCode())
            .ne(DatabusComponent::getId, currentId));
        if (count != null && count > 0) {
            throw new ServiceException("组件编码'" + bo.getComponentCode() + "'已存在");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        List<DatabusComponent> rows = componentMapper.selectByIds(ids);
        // 引用分流（§十二-5）：被链路引用的转软退役（deprecated=1 不物理删——市场安装者共享同一份
        // 组件，存量链路按 code 引用须继续可运行）；未引用的才硬删 + 清理脚本宿主
        Set<Long> retireIds = findReferencedIds(rows);
        List<Long> removeIds = rows.stream()
            .map(DatabusComponent::getId)
            .filter(id -> !retireIds.contains(id))
            .toList();
        boolean removed = false;
        if (!removeIds.isEmpty()) {
            List<String> codes = rows.stream()
                .filter(row -> removeIds.contains(row.getId()))
                .map(DatabusComponent::getComponentCode)
                .filter(StringUtils::isNotBlank)
                .toList();
            removed = componentMapper.deleteByIds(removeIds) > 0;
            if (removed) {
                // 清理脚本宿主引用（FlowBus 节点不主动摘除：硬删件残留节点无执行入口，重启消失；
                // 软退役件保留宿主，存量链路继续运行）
                codes.forEach(scriptRegistrar::forget);
            }
        }
        if (!retireIds.isEmpty()) {
            componentMapper.update(null, Wrappers.<DatabusComponent>lambdaUpdate()
                .set(DatabusComponent::getDeprecated, DEPRECATED_YES)
                .set(DatabusComponent::getDeprecateNote, DEPRECATE_NOTE_RETIRED)
                .in(DatabusComponent::getId, retireIds));
        }
        return removed || !retireIds.isEmpty();
    }

    /**
     * 引用检测（§十二-5 软退役判据，沿用原删除校验口径）：返回仍被未删除链路引用的组件 id 集合。
     * <p>链路 cmp_property 中节点形态为 {@code {"id":"<componentCode>","type":"NodeComponent",...}}，
     * 在 JSON 文本中匹配完整片段 {@code "id":"<编码>"}（引号闭合避免短编码子串误匹配）；
     * 逐码 selectCount 以定位具体被引用的组件。
     */
    private Set<Long> findReferencedIds(List<DatabusComponent> rows) {
        Set<Long> referencedIds = new HashSet<>();
        for (DatabusComponent row : rows) {
            String code = row.getComponentCode();
            if (StringUtils.isBlank(code)) {
                continue;
            }
            Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
                .like(DatabusChain::getCmpProperty, referenceLikePattern(code)));
            if (count != null && count > 0) {
                referencedIds.add(row.getId());
            }
        }
        return referencedIds;
    }

    /** cmp_property 中的精确引用片段（已转义 LIKE 通配符），外层由 MP 包裹 % 匹配 */
    private String referenceLikePattern(String componentCode) {
        return "\"id\":\"" + escapeLike(componentCode) + "\"";
    }

    /** 转义 MySQL LIKE 通配符（MySQL 默认转义符为反斜杠，无需显式 ESCAPE 子句） */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    // ------------------------------------------------------------------
    // 脚本宿主：保存即编译 / 回滚 / 版本 / 运行时健康
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScriptSaveResultVo saveScript(ScriptSaveBo bo) {
        DatabusComponent row = requireComponent(bo.getId());
        String lang = StringUtils.isBlank(bo.getScriptLang()) ? SCRIPT_LANG_JAVA : bo.getScriptLang().trim();

        // 1. 编译 + 结构校验（失败抛 ScriptCompileException，事务尚未开启写操作，自然不落库）
        ScriptArtifact artifact = scriptCompiler.compile(bo.getScriptBody());
        validateDeclaredCode(artifact, row);

        // 2. 版本表追加 + 主表工件/契约列更新
        int nextVersion = StringUtils.isBlank(row.getScriptBody())
            ? 1 : (row.getVersion() == null ? 1 : row.getVersion() + 1);
        insertVersion(row.getId(), nextVersion, lang, bo.getScriptBody(), null);
        updateScriptColumns(row, lang, bo.getScriptBody(), nextVersion, artifact);

        // 3. 热替换 FlowBus（实例化/注入/注册失败抛异常 → 回滚上面全部 DB 写，
        //    现网继续跑旧版节点，做到「能保存即可运行」）
        scriptRegistrar.register(row, artifact);
        return buildSaveResult(row.getId(), nextVersion, lang, artifact);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScriptSaveResultVo rollbackScript(ScriptRollbackBo bo) {
        DatabusComponent row = requireComponent(bo.getId());
        DatabusComponentVersion target = componentVersionMapper.selectOne(
            Wrappers.<DatabusComponentVersion>lambdaQuery()
                .eq(DatabusComponentVersion::getComponentId, bo.getId())
                .eq(DatabusComponentVersion::getVersionNo, bo.getVersionNo()));
        if (target == null) {
            throw new ServiceException("版本 v" + bo.getVersionNo() + " 不存在");
        }
        if (row.getVersion() != null && row.getVersion().equals(bo.getVersionNo())) {
            throw new ServiceException("v" + bo.getVersionNo() + " 即当前运行版本，无需回滚");
        }

        // 旧版本保存时已编译通过；若当前 classpath 下编译/注入失败，同样整体不落库
        ScriptArtifact artifact = scriptCompiler.compile(target.getScriptBody());
        validateDeclaredCode(artifact, row);

        DatabusComponentVersion latest = componentVersionMapper.selectOne(
            Wrappers.<DatabusComponentVersion>lambdaQuery()
                .eq(DatabusComponentVersion::getComponentId, bo.getId())
                .orderByDesc(DatabusComponentVersion::getVersionNo)
                .last("limit 1"));
        int nextVersion = (latest == null ? 0 : latest.getVersionNo()) + 1;
        String lang = StringUtils.isBlank(target.getScriptLang()) ? SCRIPT_LANG_JAVA : target.getScriptLang();

        insertVersion(row.getId(), nextVersion, lang, target.getScriptBody(),
            "回滚自版本 v" + target.getVersionNo());
        updateScriptColumns(row, lang, target.getScriptBody(), nextVersion, artifact);
        scriptRegistrar.register(row, artifact);
        log.info("[databus-script] 组件 {} 回滚 v{} → 新版本 v{}",
            row.getComponentCode(), target.getVersionNo(), nextVersion);
        return buildSaveResult(row.getId(), nextVersion, lang, artifact);
    }

    @Override
    public List<DatabusComponentVersionVo> queryVersions(Long componentId) {
        requireComponent(componentId);
        return componentVersionMapper.selectVoList(
            Wrappers.<DatabusComponentVersion>lambdaQuery()
                .eq(DatabusComponentVersion::getComponentId, componentId)
                .orderByDesc(DatabusComponentVersion::getVersionNo));
    }

    @Override
    public List<ScriptRuntimeVo> queryRuntimeHealth() {
        return scriptRegistrar.snapshot();
    }

    private DatabusComponent requireComponent(Long id) {
        DatabusComponent row = componentMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("组件不存在或已删除");
        }
        return row;
    }

    /**
     * 脚本 @DatabusCmp(code) 允许留空（运行 id 一律以组件表编码为准）；显式声明时必须与表编码一致，
     * 防止作者复制源码后编码漂移导致 FlowBus 注册到错误 id。
     */
    private void validateDeclaredCode(ScriptArtifact artifact, DatabusComponent row) {
        if (StringUtils.isNotBlank(artifact.declaredCode())
            && !artifact.declaredCode().equals(row.getComponentCode())) {
            throw new ServiceException("@DatabusCmp(code=\"" + artifact.declaredCode()
                + "\") 与组件编码 " + row.getComponentCode()
                + " 不一致（脚本件 code 建议留空，按组件编码注册）");
        }
    }

    private void insertVersion(Long componentId, int versionNo, String lang, String body, String remark) {
        DatabusComponentVersion version = new DatabusComponentVersion();
        version.setComponentId(componentId);
        version.setVersionNo(versionNo);
        version.setScriptLang(lang);
        version.setScriptBody(body);
        version.setRemark(remark);
        componentVersionMapper.insert(version);
    }

    /**
     * 更新主表工件三列 + 反射物化的契约缓存列；同步内存 row 供注册器读 code/version。
     * data_example 仅在非空时更新（无法以 null 清空，作者改示例须给新值）。
     */
    private void updateScriptColumns(DatabusComponent row, String lang, String body, int nextVersion,
                                     ScriptArtifact artifact) {
        DatabusComponent update = new DatabusComponent();
        update.setId(row.getId());
        update.setScriptLang(lang);
        update.setScriptBody(body);
        update.setVersion(nextVersion);
        update.setNodeType(artifact.nodeType().name());
        update.setEditor(EditorKind.FORM.jsonValue());
        update.setParamSchema(artifact.paramSchemaJson());
        if (artifact.dataExample() != null) {
            update.setDataExample(artifact.dataExample());
        }
        componentMapper.updateById(update);

        row.setScriptLang(lang);
        row.setScriptBody(body);
        row.setVersion(nextVersion);
    }

    private ScriptSaveResultVo buildSaveResult(Long componentId, int version, String lang,
                                               ScriptArtifact artifact) {
        ScriptSaveResultVo result = new ScriptSaveResultVo();
        result.setSuccess(Boolean.TRUE);
        result.setComponentId(componentId);
        result.setVersion(version);
        result.setScriptLang(lang);
        result.setNodeType(artifact.nodeType().name());
        result.setEditor(EditorKind.FORM.jsonValue());
        result.setParamSchema(artifact.paramSchemaJson());
        result.setDataExample(artifact.dataExample());
        result.setFieldCount(artifact.fields().size());
        return result;
    }

}
