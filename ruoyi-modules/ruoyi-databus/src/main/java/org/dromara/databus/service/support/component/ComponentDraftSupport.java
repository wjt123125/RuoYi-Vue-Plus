package org.dromara.databus.service.support.component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.FlowBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.component.schema.enums.EditorKind;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.bo.ScriptDraftBo;
import org.dromara.databus.domain.bo.ScriptSaveBo;
import org.dromara.databus.domain.vo.ScriptDraftResultVo;
import org.dromara.databus.domain.vo.ScriptSaveResultVo;
import org.dromara.databus.enums.ComponentStatusEnum;
import org.dromara.databus.enums.OwnerTypeEnum;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.script.host.JavaSourceCompiler;
import org.dromara.databus.script.host.ScriptArtifact;
import org.dromara.databus.script.host.ScriptComponentRegistrar;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

import static org.dromara.databus.constant.ComponentConstants.CATEGORY_DEFAULT;
import static org.dromara.databus.constant.ComponentConstants.DEPRECATED_NO;
import static org.dromara.databus.constant.ComponentConstants.SCRIPT_LANG_JAVA;

/**
 * 脚本草稿：落草稿、采纳、拒绝，以及画布预览用的临时 FlowBus 注册/摘除。
 * <p>
 * 草稿归发起人所有，只有本人能改；生效必须由人在审阅页点采纳。事务边界留在
 * {@link DatabusComponentServiceImpl}。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ComponentDraftSupport {

    /**
     * 危险 API 黑名单：只在草稿/探针结果里提示，不阻断（草稿本来就要人审）。
     * 命中一条即回传一句人话原因，让审阅人一眼看到「这段脚本动了什么不该动的」。
     */
    private static final List<DangerPattern> DANGEROUS_PATTERNS = List.of(
        new DangerPattern(Pattern.compile("System\\s*\\.\\s*exit\\s*\\("), "调用 System.exit 会终止整个服务进程"),
        new DangerPattern(Pattern.compile("Runtime\\s*\\.\\s*getRuntime\\s*\\("), "调用 Runtime 可执行系统命令或改动 JVM"),
        new DangerPattern(Pattern.compile("\\bProcessBuilder\\b"), "使用 ProcessBuilder 可启动外部进程"),
        new DangerPattern(Pattern.compile("\\bFiles\\s*\\.\\s*(write|delete|move|copy)\\s*\\("), "直接写/删本地文件"),
        new DangerPattern(Pattern.compile("new\\s+FileOutputStream\\s*\\("), "直接写本地文件"),
        new DangerPattern(Pattern.compile("\\bClass\\s*\\.\\s*forName\\s*\\("), "反射加载任意类"),
        new DangerPattern(Pattern.compile("new\\s+Thread\\s*\\("), "组件内自建线程，可能逃出链路生命周期"),
        new DangerPattern(Pattern.compile("while\\s*\\(\\s*true\\s*\\)"), "存在死循环风险")
    );

    private final DatabusComponentMapper componentMapper;

    private final JavaSourceCompiler scriptCompiler;

    private final ScriptComponentRegistrar scriptRegistrar;

    private final ComponentScriptSupport scriptSupport;

    /**
     * 落脚本草稿：编译通过才建草稿行（status=DRAFT），但不注册 FlowBus、不进物料面板。
     *
     * @param bo 草稿入参（createBy 由服务端按会话身份注入）
     * @return 草稿行信息与物化契约
     */
    public ScriptDraftResultVo saveScriptDraft(ScriptDraftBo bo) {
        if (StringUtils.isBlank(bo.getScriptBody())) {
            throw new ServiceException("脚本正文不能为空");
        }
        // 编译先行：编不过就不建草稿行，审阅页永远不会出现「点开全是红波浪线」的草稿
        ScriptArtifact artifact = scriptCompiler.compile(bo.getScriptBody());
        List<String> risks = scanDangerousApis(bo.getScriptBody());

        DatabusComponent row;
        if (bo.getId() == null) {
            String code = StringUtils.trim(bo.getComponentCode());
            if (StringUtils.isBlank(code)) {
                throw new ServiceException("新建草稿必须给组件编码 componentCode");
            }
            if (StringUtils.isBlank(bo.getComponentName())) {
                throw new ServiceException("新建草稿必须给组件名称 componentName");
            }
            Long count = componentMapper.selectCount(Wrappers.<DatabusComponent>lambdaQuery()
                .eq(DatabusComponent::getComponentCode, code));
            if (count != null && count > 0) {
                throw new ServiceException("组件编码'" + code + "'已存在，改草稿请传 componentId");
            }
            row = new DatabusComponent();
            row.setComponentCode(code);
            row.setComponentName(bo.getComponentName().trim());
            row.setCategory(StringUtils.isBlank(bo.getCategory()) ? CATEGORY_DEFAULT : bo.getCategory().trim());
            row.setGroupName(StringUtils.trimToNull(bo.getGroupName()));
            row.setDescription(StringUtils.trimToNull(bo.getDescription()));
            row.setRemark(StringUtils.trimToNull(bo.getRemark()));
            row.setStatus(ComponentStatusEnum.DRAFT.getCode());
            row.setDeprecated(DEPRECATED_NO);
            row.setOwnerType(OwnerTypeEnum.USER.getCode());
            row.setVersion(0);
            // 契约三列此刻只是「预演」：草稿不进物料面板，采纳时会随 saveScript 重算一遍
            row.setNodeType(artifact.nodeType().name());
            row.setEditor(EditorKind.FORM.jsonValue());
            row.setParamSchema(artifact.paramSchemaJson());
            row.setDataExample(artifact.dataExample());
            row.setInputParams(bo.getInputParams());
            // 正文必须落库：审阅页靠它渲染源码、试跑端点也靠它取源码，漏存等于草稿不可审不可跑
            row.setScriptLang(SCRIPT_LANG_JAVA);
            row.setScriptBody(bo.getScriptBody());
            row.setCreateBy(bo.getCreateBy());
            componentMapper.insert(row);
        } else {
            row = scriptSupport.requireComponent(bo.getId());
            if (!ComponentStatusEnum.DRAFT.getCode().equals(row.getStatus())) {
                throw new ServiceException("只能更新草稿组件，当前状态为 " + row.getStatus()
                    + "；要改已启用的件请走脚本编辑与保存");
            }
            if (bo.getCreateBy() != null && !bo.getCreateBy().equals(row.getCreateBy())) {
                throw new ServiceException("只能更新自己创建的草稿");
            }
            DatabusComponent update = new DatabusComponent();
            update.setId(row.getId());
            update.setComponentName(StringUtils.trimToNull(bo.getComponentName()));
            update.setGroupName(StringUtils.trimToNull(bo.getGroupName()));
            update.setDescription(StringUtils.trimToNull(bo.getDescription()));
            update.setRemark(StringUtils.trimToNull(bo.getRemark()));
            if (StringUtils.isNotBlank(bo.getCategory())) {
                update.setCategory(bo.getCategory().trim());
            }
            update.setScriptBody(bo.getScriptBody());
            update.setNodeType(artifact.nodeType().name());
            update.setEditor(EditorKind.FORM.jsonValue());
            update.setParamSchema(artifact.paramSchemaJson());
            if (artifact.dataExample() != null) {
                update.setDataExample(artifact.dataExample());
            }
            // 入参登记：不传＝保留原登记，传了才覆盖（含显式空数组＝清空）
            if (bo.getInputParams() != null) {
                update.setInputParams(bo.getInputParams());
            }
            componentMapper.updateById(update);
            row.setScriptBody(bo.getScriptBody());
            if (bo.getInputParams() != null) {
                row.setInputParams(bo.getInputParams());
            }
        }

        ScriptDraftResultVo result = new ScriptDraftResultVo();
        result.setId(row.getId());
        result.setComponentCode(row.getComponentCode());
        result.setComponentName(row.getComponentName());
        result.setStatus(ComponentStatusEnum.DRAFT.getCode());
        result.setNodeType(artifact.nodeType().name());
        result.setParamSchema(artifact.paramSchemaJson());
        result.setDataExample(artifact.dataExample());
        result.setInputParams(row.getInputParams());
        result.setFieldCount(artifact.fields().size());
        result.setRisks(risks);
        log.info("[databus-script] 脚本草稿已落库 id={}, code={}, 发起人={}",
            row.getId(), row.getComponentCode(), bo.getCreateBy());
        return result;
    }

    /**
     * 采纳脚本草稿：校验草稿态 → 先转 ENABLED → 再走一遍 {@link ComponentScriptSupport#saveScript} 保存管线。
     *
     * @param id 草稿组件主键
     * @return 新版本号与物化契约
     */
    public ScriptSaveResultVo adoptScriptDraft(Long id) {
        DatabusComponent row = scriptSupport.requireComponent(id);
        if (!ComponentStatusEnum.DRAFT.getCode().equals(row.getStatus())) {
            throw new ServiceException("只有草稿组件可以采纳，当前状态为 " + row.getStatus());
        }
        if (StringUtils.isBlank(row.getScriptBody())) {
            throw new ServiceException("草稿没有脚本正文，无法采纳");
        }
        // 先转状态再 saveScript：saveScript 内会按 contract 重新编译并注册 FlowBus，
        // 任一步失败整个事务回滚，不会留下「已启用但跑不起来」的件
        componentMapper.update(null, Wrappers.<DatabusComponent>lambdaUpdate()
            .set(DatabusComponent::getStatus, ComponentStatusEnum.ENABLED.getCode())
            .eq(DatabusComponent::getId, id));
        ScriptSaveBo saveBo = new ScriptSaveBo();
        saveBo.setId(id);
        saveBo.setScriptLang(StringUtils.isBlank(row.getScriptLang()) ? SCRIPT_LANG_JAVA : row.getScriptLang());
        saveBo.setScriptBody(row.getScriptBody());
        ScriptSaveResultVo result = scriptSupport.saveScript(saveBo);
        log.info("[databus-script] 脚本草稿已采纳 code={}", row.getComponentCode());
        return result;
    }

    /**
     * 拒绝草稿：物理删掉 DRAFT 行（从未注册 FlowBus、未被引用，删除无副作用）。
     *
     * @param id 草稿组件主键
     * @return 是否删除成功
     */
    public Boolean rejectScriptDraft(Long id) {
        DatabusComponent row = scriptSupport.requireComponent(id);
        if (!ComponentStatusEnum.DRAFT.getCode().equals(row.getStatus())) {
            throw new ServiceException("只有草稿组件可以拒绝，当前状态为 " + row.getStatus());
        }
        // 草稿从未注册 FlowBus、未被链路引用、没有版本行，必须真删：逻辑删会留下 del_flag=1 的行，
        // 而唯一键 uk_component_code 不含 del_flag，这个编码就再也建不了草稿
        boolean removed = componentMapper.deletePhysicallyByIds(List.of(id)) > 0;
        if (removed) {
            scriptRegistrar.forget(row.getComponentCode());
            log.info("[databus-script] 脚本草稿已拒绝删除 code={}", row.getComponentCode());
        }
        return removed;
    }

    /**
     * 把草稿行按真实组件编码临时注册进 FlowBus（只造实例 + 登记，不写库、不进台账）。
     *
     * @param draftId 草稿组件主键
     * @return 注册用的 nodeId（即组件编码）
     */
    public String registerDraftForPreview(Long draftId) {
        DatabusComponent row = scriptSupport.requireComponent(draftId);
        if (!ComponentStatusEnum.DRAFT.getCode().equals(row.getStatus())) {
            throw new ServiceException("只有草稿组件支持画布预览，当前状态为 " + row.getStatus());
        }
        if (StringUtils.isBlank(row.getScriptBody())) {
            throw new ServiceException("该草稿没有脚本正文，无法预览");
        }
        ScriptArtifact artifact = scriptCompiler.compile(row.getScriptBody());
        validateDeclaredCode(artifact, row);
        String nodeId = row.getComponentCode();
        // 同编码已注册时不能硬替换：预览跑完会摘除节点，等于把线上组件一起删掉
        if (FlowBus.getNode(nodeId) != null) {
            throw new ServiceException("组件编码 " + nodeId + " 已被已注册组件占用，无法预览");
        }
        NodeComponent instance = scriptRegistrar.instantiate(artifact);
        try {
            FlowBus.addManagedNode(nodeId, instance);
        } catch (Exception e) {
            throw new ServiceException("预览节点注册 FlowBus 失败：" + e.getMessage(), e);
        }
        log.info("[databus-script] 预览草稿临时注册 code={} id={}", nodeId, draftId);
        return nodeId;
    }

    /**
     * 摘除预览注册的草稿节点；失败只 warn（执行结果已拿到）。
     *
     * @param nodeId 注册时返回的 nodeId
     */
    public void unregisterDraftForPreview(String nodeId) {
        if (StringUtils.isBlank(nodeId)) {
            return;
        }
        try {
            FlowBus.removeNode(nodeId);
        } catch (Exception e) {
            // 清理是收尾动作：结果已经拿到，摘失败只留痕
            log.warn("[databus-script] 预览草稿节点清理失败 nodeId={}：{}", nodeId, e.getMessage());
        }
    }

    /**
     * 脚本 @DatabusCmp(code) 显式声明时必须与组件表编码一致，防止预览注册到错误 id。
     */
    private void validateDeclaredCode(ScriptArtifact artifact, DatabusComponent row) {
        if (StringUtils.isNotBlank(artifact.declaredCode())
            && !artifact.declaredCode().equals(row.getComponentCode())) {
            throw new ServiceException("@DatabusCmp(code=\"" + artifact.declaredCode()
                + "\") 与组件编码 " + row.getComponentCode()
                + " 不一致（脚本件 code 建议留空，按组件编码注册）");
        }
    }

    /**
     * 探针入参：空串按空对象跑；非空必须是合法 JSON，否则直接给人话错误（别把解析异常甩给模型）。
     */
    private Object parseProbeRequest(String requestData) {
        if (StringUtils.isBlank(requestData)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return JsonUtils.parseObject(requestData, Object.class);
        } catch (Exception e) {
            throw new ServiceException("requestData 不是合法 JSON：" + e.getMessage(), e);
        }
    }

    /**
     * 危险 API 自查：命中即回传人话原因，不阻断落草稿/试跑——草稿本来就要人审。
     */
    private List<String> scanDangerousApis(String source) {
        if (StringUtils.isBlank(source)) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        for (DangerPattern danger : DANGEROUS_PATTERNS) {
            if (danger.pattern().matcher(source).find()) {
                hits.add(danger.reason());
            }
        }
        return hits;
    }

    /**
     * 危险 API 黑名单条目：正则 + 命中原因
     */
    private record DangerPattern(Pattern pattern, String reason) {
    }

}
