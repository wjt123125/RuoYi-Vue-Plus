package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * BO_CREATE 入参中的一项：BO 名称 + 待创建记录列表（与 BPM 端 {@code com.awspaas.databus.connector.model.BoItem} 字段一致）。
 *
 * <p>records 中每个 Map 的 key 已是 BPM 字段名、value 已是正确类型
 * （决策 9.1 + 决策 4：字段重映射与类型转换在 Component 层 fieldMap 增强完成，
 * BPM 端 BO.set 时 SDK 不做类型转换，原值透传）。
 *
 * @author databus
 */
@Data
public class BoItem {

    /** BO 名称，必填 */
    private String boName;

    /** 待创建记录列表，必填。每项 Map 的 key 是 BPM 字段名 */
    private List<Map<String, Object>> records;
}
