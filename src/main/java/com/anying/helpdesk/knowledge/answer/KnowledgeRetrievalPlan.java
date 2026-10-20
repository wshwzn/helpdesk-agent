package com.anying.helpdesk.knowledge.answer;

import java.util.List;

/** 规划只描述待查事实和搜索词，不生成可交付答案或修改知识库及范围。 */
public record KnowledgeRetrievalPlan(Decision decision, List<String> gaps, List<String> queries) {
    public enum Decision { SEARCH, SUFFICIENT, NEEDS_USER_INPUT, EXHAUSTED }

    /** 严格限制规划规模及动作组合，错误协议不能被当作证据充分。 */
    public void validate() {
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
}
