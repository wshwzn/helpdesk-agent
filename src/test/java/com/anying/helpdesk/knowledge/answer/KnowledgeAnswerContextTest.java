package com.anying.helpdesk.knowledge.answer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class KnowledgeAnswerContextTest {
    /** 已知制度表缺失不能转换成对员工类别或内部映射的追问。 */
    @Test @DisplayName("内部资料缺失不授权向用户追问")
    void knowledgeGapDoesNotAuthorizeClarification() {
        var context = new KnowledgeAnswerContext(KnowledgeRetrievalRun.StopReason.NO_NEW_EVIDENCE,
                List.of("缺失内部规则表"), List.of(), List.of(), true);
        assertThatThrownBy(() -> context.validate(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.NEEDS_CLARIFICATION,
                List.of(), List.of("请提供规则等级？"), List.of())))
                .hasMessage("clarification requires an assessed user condition");
        context.validate(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.INSUFFICIENT_EVIDENCE, List.of(), List.of(), List.of()));
    }

    /** 真实需要用户决定的条件仍可询问，不把所有澄清一概禁用。 */
    @Test @DisplayName("明确用户条件允许澄清而非自动拒答")
    void genuineUserConditionAllowsClarification() {
        var context = new KnowledgeAnswerContext(KnowledgeRetrievalRun.StopReason.NEEDS_USER_INPUT,
                List.of(), List.of("出差员工类别"), List.of(), false);
        context.validate(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.NEEDS_CLARIFICATION,
                List.of(), List.of("请确认员工类别？"), List.of()));
    }

    /** 补查可能已填补旧缺口，不能按过期规划强行把正确完整答案降为不足。 */
    @Test @DisplayName("补查后的旧缺口不是强制拒答依据")
    void staleGapsDoNotForceInsufficiency() {
        var context = new KnowledgeAnswerContext(KnowledgeRetrievalRun.StopReason.ROUND_BUDGET,
                List.of("此前缺映射"), List.of(), List.of(), true);
        context.validate(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWERABLE, List.of(), List.of(), List.of()));
    }
}
