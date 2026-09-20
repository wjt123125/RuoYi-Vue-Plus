package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.FileDownloadRequest;

import java.util.Map;

/**
 * FILE_DOWNLOAD 组件（注册名 {@code fileDownload}）。
 *
 * <p>按 boId + fieldName 通过 BPM connector 读取 BO 记录附件字段的全部文件（base64），
 * 对应老系统 FileToBase64Processor（旧命名方向相反）。
 *
 * <p>响应存 {@code $.<tag>.fileCount} 与 {@code $.<tag>.files}；
 * files 可直接作为 fileUpload 的 sourcePath，附件搬运链无需中间转换。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("fileDownload")
public class FileDownloadComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        FileDownloadCfg cfg = this.getCmpData(FileDownloadCfg.class);
        if (cfg == null) {
            throw new ServiceException("FILE_DOWNLOAD 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("FILE_DOWNLOAD 组件缺少数据空间标识（tag）");
        }
        String boId = resolveStr(cfg.getBoId());
        String fieldName = resolveStr(cfg.getFieldName());
        if (isBlank(cfg.getConnectionId()) || isBlank(boId) || isBlank(fieldName)) {
            throw new ServiceException("FILE_DOWNLOAD 组件缺少 connectionId / boId / fieldName 配置（tag=" + tag + "）");
        }

        FileDownloadRequest request = new FileDownloadRequest();
        request.setBoId(boId);
        request.setFieldName(fieldName);

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(resolveStr(cfg.getConnectionId()));

        Object result = connector.fileDownload(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("FILE_DOWNLOAD 响应非 JSON 对象: " + result);
        }
        Object fileCount = resultMap.get("fileCount");
        save("$." + tag + ".fileCount", fileCount);
        save("$." + tag + ".files", resultMap.get("files"));

        int count = fileCount instanceof Number number ? number.intValue() : 0;
        resultSummary(count == 0 ? "下载 0 个附件（字段无文件）" : "下载 " + count + " 个附件");
        log.info("[databus] fileDownload 完成 tag={} count={}", tag, count);
    }

    /**
     * 字符串参数解析：纯路径读取 / 混合路径替换 / 字面量原样返回，统一转 String。
     */
    private String resolveStr(Object input) {
        if (input == null) {
            return null;
        }
        Object resolved = resolveParam(input);
        return resolved == null ? null : resolved.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
