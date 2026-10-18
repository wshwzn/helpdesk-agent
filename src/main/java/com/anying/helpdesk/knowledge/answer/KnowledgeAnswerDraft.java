package com.anying.helpdesk.knowledge.answer;

import java.util.List;

/** 模型提出的知识回答草案；只有经过程序校验和语义复核后才能展示。 */
public record KnowledgeAnswerDraft(
        Status status,
        List<Claim> claims,
        List<String> missingConditions,
        List<Conflict> conflicts
) {
    public enum Status { ANSWERABLE, NEEDS_CLARIFICATION, INSUFFICIENT_EVIDENCE, CONFLICT }

    public record Claim(String text, List<Reference> references) { }

    public record Reference(String evidenceId, String quote) { }

    public record Conflict(String issue, Claim first, Claim second) { }
}
