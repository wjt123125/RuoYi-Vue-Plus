package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * BO_UPDATE 请求入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.BoUpdateRequest} 字段一致）。
 * <p>
 * 复用 {@link BoItem}，区别于 BO_CREATE：<b>records 中每条记录必须含 ID 字段</b>
 * （BO 按记录 ID 定位更新），其余键值对为待更新字段。字段别名/类型转换由
 * Component 层 fieldMap 完成，BPM 端原值透传。
 *
 * @author databus
 */
@Data
public class BoUpdateRequest {

    /** BO 列表，必填。每项含 boName + records（每条 records 必须含 ID） */
    private List<BoItem> boList;
}
