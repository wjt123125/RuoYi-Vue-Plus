package com.aizuda.snail.ai.starter.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * SSE 客户端断连异常处理
 * <p>
 * 浏览器关闭页面或停止生成时，Tomcat 回写会抛 ClientAbortException，
 * Spring MVC 将其包装为 AsyncRequestNotUsableException 交给异常解析器。
 * 若不在此拦截，异常会落到 OpenApiExceptionHandler 的兜底分支，
 * 向已经是 text/event-stream 的响应写 JSON，进而报 HttpMessageNotWritableException。
 *
 * @author databus
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class AsyncRequestNotUsableExceptionHandler {

    /**
     * 客户端已断开，响应不可再写，直接吞掉。
     *
     * @param ex 断连异常
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void onClientDisconnected(AsyncRequestNotUsableException ex) {
        log.debug("SSE client disconnected: {}", ex.getMessage());
    }

}