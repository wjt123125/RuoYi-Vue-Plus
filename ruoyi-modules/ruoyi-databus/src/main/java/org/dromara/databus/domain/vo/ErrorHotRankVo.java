package org.dromara.databus.domain.vo;

import lombok.Data;

/**
 * 错误热点排行榜行（窗口内按错误指纹发生次数倒序）。
 * <p>
 * 同一链路下指纹相同的错误已归并计数，error_sample 取该指纹下的一条原始样本。
 *
 * @author databus
 */
@Data
public class ErrorHotRankVo {

    /**
     * 链路编码。
     */
    private String chainCode;

    /**
     * 错误指纹（归一化后的错误首行，抹除数字噪声）。
     */
    private String errorFingerprint;

    /**
     * 原始错误样本（截断到 500 字符）。
     */
    private String errorSample;

    /**
     * 窗口内该指纹发生次数。
     */
    private Integer happenCount;

}