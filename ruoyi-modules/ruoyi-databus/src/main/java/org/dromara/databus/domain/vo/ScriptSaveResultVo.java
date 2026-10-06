package org.dromara.databus.domain.vo;

import lombok.Data;
import org.dromara.databus.script.host.ScriptDiagnostic;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 脚本保存/回滚结果（成功与编译失败统一包络，HTTP/R 码均为成功）。
 * <p>
 * 编译失败不走 R.fail：前端响应拦截器对业务失败码只透传 msg、丢弃 data，
 * 行列级诊断无法到达编辑器；故失败时仍以 R.ok 返回本对象，success=false，
 * 由表单自行渲染诊断区。
 *
 * @author databus
 */
@Data
public class ScriptSaveResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否保存成功（编译通过且已落库热更）
     */
    private Boolean success;

    /**
     * 失败总述（success=false 时有值）
     */
    private String message;

    /**
     * 编译诊断明细（行列号，供编辑器标红）
     */
    private List<ScriptDiagnostic> diagnostics;

    /**
     * 组件主键
     */
    private Long componentId;

    /**
     * 新版本号
     */
    private Integer version;

    /**
     * 脚本语言
     */
    private String scriptLang;

    /**
     * 物化：节点类型
     */
    private String nodeType;

    /**
     * 物化：配置形态（固定 form）
     */
    private String editor;

    /**
     * 物化：参数 schema 体
     */
    private String paramSchema;

    /**
     * 物化：配置示例
     */
    private String dataExample;

    /**
     * 反射出的字段数
     */
    private Integer fieldCount;

    /**
     * 编译失败工厂：携带总述与行列诊断，不产生任何写操作。
     */
    public static ScriptSaveResultVo fail(String message, List<ScriptDiagnostic> diagnostics) {
        ScriptSaveResultVo vo = new ScriptSaveResultVo();
        vo.setSuccess(Boolean.FALSE);
        vo.setMessage(message);
        vo.setDiagnostics(diagnostics);
        return vo;
    }

}
