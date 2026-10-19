package com.anying.helpdesk.knowledge.answer;

/** 以固定枚举标识知识回答失败阶段，禁止把厂商正文或草案写入消息错误码。 */
public class KnowledgeAnswerFailure extends IllegalStateException {
    public enum Code {
        KNOWLEDGE_RETRIEVAL_FAILED,
        KNOWLEDGE_DRAFT_CALL_FAILED,
        KNOWLEDGE_REVIEW_CALL_FAILED,
        KNOWLEDGE_PROTOCOL_INVALID,
        KNOWLEDGE_CITATION_INVALID,
        KNOWLEDGE_STRUCTURE_INVALID,
        KNOWLEDGE_REVIEW_REJECTED,
        KNOWLEDGE_REVIEW_UNSUPPORTED_CLAIM,
        KNOWLEDGE_REVIEW_WRONG_STATUS,
        KNOWLEDGE_REVIEW_MISSED_CONFLICT,
        KNOWLEDGE_REVIEW_INCOMPLETE_ANSWER,
        KNOWLEDGE_REVIEW_UNNECESSARY_CLARIFICATION,
        KNOWLEDGE_REVIEW_UNCERTAIN,
        KNOWLEDGE_EVIDENCE_CHANGED,
        KNOWLEDGE_EVIDENCE_CHECK_FAILED,
        KNOWLEDGE_CONTEXT_TOO_LARGE
    }

    private final Code code;

    /** 使用固定错误码及内部说明记录可诊断的失败，不携带原始模型异常。 */
    public KnowledgeAnswerFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回可以安全持久化到用户消息历史的分类码。 */
    public String errorCode() {
        return code.name();
    }
}
