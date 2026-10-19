package com.anying.helpdesk.knowledge.answer;

/** 澄清配置或续答状态不合法，拒绝在错误上下文中开始生成。 */
public class KnowledgeClarificationException extends RuntimeException {
    private final boolean conflict;
    /** 区分参数错误与过期上下文，响应不包含其他会话的信息。 */
    public KnowledgeClarificationException(boolean conflict, String message) {
        super(message);
        this.conflict = conflict;
    }
    /** 告诉接口层本次错误是否需要重新读取配置或重新提问。 */
    public boolean conflict() { return conflict; }
}
