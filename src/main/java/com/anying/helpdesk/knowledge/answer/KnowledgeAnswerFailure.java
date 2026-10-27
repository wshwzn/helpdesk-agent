package com.anying.helpdesk.knowledge.answer;

/** 以固定枚举标识知识回答失败阶段，禁止把厂商正文或草案写入消息错误码。 */
public class KnowledgeAnswerFailure extends IllegalStateException {
    public enum Code {
        KNOWLEDGE_APPLICABILITY_CALL_FAILED,
        KNOWLEDGE_APPLICABILITY_UNCERTAIN,
        KNOWLEDGE_APPLICABILITY_CHANGED,
        KNOWLEDGE_RETRIEVAL_FAILED,
        KNOWLEDGE_PLANNING_CALL_FAILED,
        KNOWLEDGE_PLAN_PAYLOAD_INVALID,
        KNOWLEDGE_PLAN_FIELDS_INVALID,
        KNOWLEDGE_PLAN_ACTION_INVALID,
        KNOWLEDGE_PLAN_RULE_QUERY_INVALID,
        KNOWLEDGE_PLAN_RULE_ANCHOR_INVALID,
        KNOWLEDGE_UNJUSTIFIED_CLARIFICATION,
        KNOWLEDGE_RULE_CHECK_INCOMPLETE,
        KNOWLEDGE_SEARCH_INCOMPLETE,
        KNOWLEDGE_DRAFT_CALL_FAILED,
        KNOWLEDGE_REVISION_CALL_FAILED,
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
    private final KnowledgeAnswerModelClient.TokenUsage usage;

    /** 使用固定错误码及内部说明记录可诊断的失败，不携带原始模型异常。 */
    public KnowledgeAnswerFailure(Code code, String message) {
        this(code, message, null);
    }

    /** 协议失败仍保留厂商已返回的用量，绝不附带原始响应或密钥。 */
    public KnowledgeAnswerFailure(Code code, String message, KnowledgeAnswerModelClient.TokenUsage usage) {
        super(message);
        this.code = code;
        this.usage = usage;
    }

    /** 补充已知用量时保留原固定错误分类，不把字段错误重新压成通用错误。 */
    public KnowledgeAnswerFailure withUsage(KnowledgeAnswerModelClient.TokenUsage value) {
        return new KnowledgeAnswerFailure(code, getMessage(), value);
    }

    /** 提供失败调用中已知的用量，未知时返回空值。 */
    public KnowledgeAnswerModelClient.TokenUsage usage() {
        return usage;
    }

    /** 返回可以安全持久化到用户消息历史的分类码。 */
    public String errorCode() {
        return code.name();
    }
}
