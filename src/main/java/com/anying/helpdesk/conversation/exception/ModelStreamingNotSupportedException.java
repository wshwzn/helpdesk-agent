package com.anying.helpdesk.conversation.exception;

public class ModelStreamingNotSupportedException extends RuntimeException {

    /**
     * 创建默认模型不支持流式输出的业务异常。
     */
    public ModelStreamingNotSupportedException() {
        super("the default model configuration does not support streaming");
    }
}
