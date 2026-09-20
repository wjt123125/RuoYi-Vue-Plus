package org.dromara.databus.component.bpm;

import cn.hutool.crypto.digest.DigestUtil;
import com.jayway.jsonpath.TypeRef;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.FileUploadRequest;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FILE_UPLOAD 组件（注册名 {@code fileUpload}）。
 *
 * <p>从数据空间读取文件数组（fileName + base64 fileContent），逐文件先在本地完成
 * 可选的摘要校验（md5/sha1/sha256/sha512），再通过 BPM connector 上传到目标 BO 记录附件字段。
 * 对应老系统 Base64ToFileProcessor（旧命名方向相反）；旧 processor 的全局单值校验
 * 改为逐文件携带 checkMethod/checksum。
 *
 * <p>响应存 {@code $.<tag>.uploadedCount} 与 {@code $.<tag>.files}。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("fileUpload")
public class FileUploadComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        FileUploadCfg cfg = this.getCmpData(FileUploadCfg.class);
        if (cfg == null) {
            throw new ServiceException("FILE_UPLOAD 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("FILE_UPLOAD 组件缺少数据空间标识（tag）");
        }
        if (isBlank(cfg.getConnectionId()) || isBlank(cfg.getSourcePath())) {
            throw new ServiceException("FILE_UPLOAD 组件缺少 connectionId / sourcePath 配置（tag=" + tag + "）");
        }
        String boId = resolveStr(cfg.getBoId());
        String appId = resolveStr(cfg.getAppId());
        String boName = resolveStr(cfg.getBoName());
        String boItemName = resolveStr(cfg.getBoItemName());
        if (isBlank(boId) || isBlank(appId) || isBlank(boName) || isBlank(boItemName)) {
            throw new ServiceException("FILE_UPLOAD 组件缺少 boId / appId / boName / boItemName 配置（tag=" + tag + "）");
        }

        List<Map<String, Object>> sourceFiles =
                get(cfg.getSourcePath(), new TypeRef<List<Map<String, Object>>>() {});
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new ServiceException("FILE_UPLOAD 源文件列表为空：" + cfg.getSourcePath());
        }

        boolean requireChecksum = Boolean.TRUE.equals(cfg.getValidateChecksum());
        List<FileUploadRequest.FileUploadItem> items = new ArrayList<>();
        for (Map<String, Object> src : sourceFiles) {
            String fileName = strOf(src.get("fileName"));
            String fileContent = strOf(src.get("fileContent"));
            if (isBlank(fileName) || isBlank(fileContent)) {
                throw new ServiceException("FILE_UPLOAD 文件项缺少 fileName / fileContent（tag=" + tag + "）");
            }

            String checkMethod = strOf(src.get("checkMethod"));
            String checksum = strOf(src.get("checksum"));
            if (!isBlank(checkMethod) || !isBlank(checksum)) {
                verifyChecksum(fileName, fileContent, checkMethod, checksum);
            } else if (requireChecksum) {
                throw new ServiceException("文件 " + fileName + " 缺少 checkMethod / checksum（validateChecksum=true）");
            }

            FileUploadRequest.FileUploadItem item = new FileUploadRequest.FileUploadItem();
            item.setFileName(fileName);
            item.setFileContent(fileContent);
            Object securityLevel = src.get("securityLevel");
            if (securityLevel instanceof Number number) {
                item.setSecurityLevel(number.intValue());
            }
            items.add(item);
        }

        FileUploadRequest request = new FileUploadRequest();
        request.setBoId(boId);
        request.setAppId(appId);
        request.setBoName(boName);
        request.setBoItemName(boItemName);
        request.setProcessInstId(blankToNull(resolveStr(cfg.getProcessInstId())));
        request.setTaskInstId(blankToNull(resolveStr(cfg.getTaskInstId())));
        request.setFiles(items);

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(resolveStr(cfg.getConnectionId()));

        Object result = connector.fileUpload(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("FILE_UPLOAD 响应非 JSON 对象: " + result);
        }
        save("$." + tag + ".uploadedCount", resultMap.get("uploadedCount"));
        save("$." + tag + ".files", resultMap.get("files"));
        resultSummary("上传 " + items.size() + " 个附件");
        log.info("[databus] fileUpload 完成 tag={} count={}", tag, items.size());
    }

    /**
     * 本地摘要校验：base64 解码后按 checkMethod 计算实际摘要，与期望 checksum 十六进制忽略大小写比较。
     */
    private void verifyChecksum(String fileName, String fileContent, String checkMethod, String expected) {
        if (isBlank(checkMethod) || isBlank(expected)) {
            throw new ServiceException("文件 " + fileName + " 校验配置不完整：checkMethod / checksum 需同时提供");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(fileContent);
        } catch (Exception e) {
            throw new ServiceException("文件 " + fileName + " base64 解码失败: " + e.getMessage());
        }
        String actual = switch (checkMethod.toLowerCase(Locale.ROOT)) {
            case "md5" -> DigestUtil.md5Hex(bytes);
            case "sha1" -> DigestUtil.sha1Hex(bytes);
            case "sha256" -> DigestUtil.sha256Hex(bytes);
            case "sha512" -> DigestUtil.sha512Hex(bytes);
            default -> throw new ServiceException("文件 " + fileName + " 不支持的摘要方法: " + checkMethod);
        };
        if (!actual.equalsIgnoreCase(expected)) {
            throw new ServiceException(String.format(
                    "文件 %s 摘要校验失败：预期 %s=%s，实际 %s", fileName, checkMethod, expected, actual));
        }
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

    private static String strOf(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
