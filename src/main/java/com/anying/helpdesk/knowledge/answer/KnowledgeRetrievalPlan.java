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

    /** 新模型只生成一份任务列表；转换后复用既有诊断 queries 字段，旧核查字段仅供历史读取。 */
    public record TaskPlan(Decision decision, List<String> gaps, List<String> tasks, List<String> userConditions) {
        /** 将单列表协议转为内部统一计划，不接受第二份规则核查任务。 */
        public KnowledgeRetrievalPlan toPlan() {
            var result = new KnowledgeRetrievalPlan(decision, gaps, tasks, List.of(), userConditions);
            result.validate();
            return result;
        }
    }

    /** 单列表只在 SEARCH 时非空，停止决策不能隐含另一批待查任务。 */
    public void validate() {
        if (decision == null || gaps == null || queries == null || ruleChecks == null || userConditions == null
                || gaps.size() > 8 || queries.size() > 2 || !ruleChecks.isEmpty() || userConditions.size() > 5
                || gaps.stream().anyMatch(s -> invalidText(s, 300, false))
                || queries.stream().anyMatch(s -> invalidText(s, 300, true))
                || userConditions.stream().anyMatch(s -> invalidText(s, 300, false)))
            throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_PLAN_FIELDS_INVALID,
                    "invalid retrieval plan fields");
        if ((decision == Decision.SEARCH && (queries.isEmpty() || gaps.isEmpty()))
                || (decision != Decision.SEARCH && !queries.isEmpty())
                || (decision == Decision.SUFFICIENT && (!gaps.isEmpty() || !userConditions.isEmpty()))
                || (decision == Decision.NEEDS_USER_INPUT && userConditions.isEmpty())
                || (decision == Decision.EXHAUSTED && gaps.isEmpty()))
            throw new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_PLAN_ACTION_INVALID,
                    "invalid retrieval plan action");
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

    /** 统一查询格式用于判重，查询本身不作为可引用事实。 */
    public static String normalize(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
