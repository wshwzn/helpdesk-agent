package com.anying.helpdesk.knowledge.answer;

import java.util.List;

/** 规划只描述待查事实和搜索词，不生成可交付答案或修改知识库及范围。 */
public record KnowledgeRetrievalPlan(Decision decision, List<String> gaps, List<String> queries,
        List<RuleCheck> ruleChecks, List<String> userConditions) {
    public enum Decision { SEARCH, SUFFICIENT, NEEDS_USER_INPUT, EXHAUSTED }
    public record RuleCheck(String evidenceId, String term, String query) { }

    /** 兼容旧诊断和调用方构造；新模型协议必须显式提供新增列表。 */
    public KnowledgeRetrievalPlan(Decision decision, List<String> gaps, List<String> queries) {
        this(decision, gaps, queries, List.of(), List.of());
    }

    /** 分开标识字段、动作和查询绑定错误，不保存模型原始输出。 */
    public void validate() {
        if (decision == null || gaps == null || queries == null || ruleChecks == null || userConditions == null
                || gaps.size() > 8 || queries.size() > 2 || ruleChecks.size() > 2 || userConditions.size() > 5
                || gaps.stream().anyMatch(s -> invalidText(s, 300, false))
                || queries.stream().anyMatch(s -> invalidText(s, 300, true))
                || userConditions.stream().anyMatch(s -> invalidText(s, 300, false))
                || ruleChecks.stream().anyMatch(c -> c == null || c.evidenceId() == null
                    || invalidText(c.term(), 100, false) || invalidText(c.query(), 300, true)))
            throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_PLAN_FIELDS_INVALID,
                    "invalid retrieval plan fields");
        if ((decision == Decision.SEARCH && (queries.isEmpty() || gaps.isEmpty()))
                || (decision != Decision.SEARCH && !queries.isEmpty())
                || (decision == Decision.SUFFICIENT && (!gaps.isEmpty() || !userConditions.isEmpty() || ruleChecks.isEmpty()))
                || (decision == Decision.NEEDS_USER_INPUT && userConditions.isEmpty())
                || (decision == Decision.EXHAUSTED && gaps.isEmpty()))
            throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_PLAN_ACTION_INVALID,
                    "invalid retrieval plan action");
        if (ruleChecks.stream().anyMatch(c -> !normalize(c.query()).contains(normalize(c.term()))))
            throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_PLAN_RULE_QUERY_INVALID,
                    "rule query does not contain its term");
    }

    /** 对各字段沿用原长度和控制字符限制，不因诊断细分放宽契约。 */
    private boolean invalidText(String value, int limit, boolean singleLine) {
        return value == null || value.isBlank() || value.length() > limit
                || (singleLine && value.codePoints().anyMatch(Character::isISOControl));
    }

    /** 保留阶段 37 的规划边界，未启用实验协议时不要求新增字段。 */
    public void validateLegacy() {
        if (decision == null || gaps == null || queries == null || gaps.size() > 8 || queries.size() > 2
                || gaps.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 300)
                || queries.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 300
                    || s.codePoints().anyMatch(Character::isISOControl))
                || (decision == Decision.SEARCH && (queries.isEmpty() || gaps.isEmpty()))
                || (decision != Decision.SEARCH && !queries.isEmpty())
                || (decision == Decision.SUFFICIENT && !gaps.isEmpty())
                || (decision != Decision.SUFFICIENT && gaps.isEmpty())) {
            throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_PROTOCOL_INVALID,
                    "invalid retrieval plan");
        }
    }

    /** 规则词必须真实出现于本轮原文，不能凭空声明已识别的规则或跨轮引用。 */
    public void validateEvidence(List<KnowledgeAnswerReport.Evidence> evidence) {
        validate();
        for (var check : ruleChecks) {
            if (evidence.stream().noneMatch(e -> e.id().equals(check.evidenceId()) && e.content().contains(check.term())))
                throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_PLAN_RULE_ANCHOR_INVALID,
                        "rule check has no evidence anchor");
        }
    }

    /** 统一查询和规则词的格式比较，原文锚点本身仍要求逐字匹配。 */
    public static String normalize(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
