package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class KnowledgeScopeConflictTest {
    /** 单纯上海与北京金额不同不得伪装成需要维护者裁决的制度冲突。 */
    @Test @DisplayName("不同地区的金额差异不能构造同条件冲突")
    void rejectsDisjointScopes() {
        assertThatThrownBy(() -> new KnowledgeAnswerGuard().validateScopeConflicts(draft(), evidence("CN_SH", "CN_BJ")))
                .isInstanceOf(KnowledgeAnswerFailure.class);
    }
    /** 通用规定与特定地区规定可能冲突，保留到语义复核而非直接判定不冲突。 */
    @Test @DisplayName("通用与具体范围重叠时交由语义复核判断")
    void permitsOverlappingScopesForReview() {
        assertThatCode(() -> new KnowledgeAnswerGuard().validateScopeConflicts(draft(), evidence("ALL", "CN_BJ")))
                .doesNotThrowAnyException();
    }
    /** 创建包含两份独立文件引用的候选冲突。 */
    private KnowledgeAnswerDraft draft() {
        return new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.CONFLICT, List.of(), List.of(), List.of(
                new KnowledgeAnswerDraft.Conflict("金额", new KnowledgeAnswerDraft.Claim("600", List.of(new KnowledgeAnswerDraft.Reference("E1", "600"))),
                        new KnowledgeAnswerDraft.Claim("500", List.of(new KnowledgeAnswerDraft.Reference("E2", "500"))))));
    }
    /** 构造不同适用范围的候选证据。 */
    private List<KnowledgeAnswerReport.Evidence> evidence(String first, String second) {
        return List.of(new KnowledgeAnswerReport.Evidence("E1", 1L, 0, "a", "600", new KnowledgeDocumentMetadata("a", "v1", first, null, null)),
                new KnowledgeAnswerReport.Evidence("E2", 2L, 0, "b", "500", new KnowledgeDocumentMetadata("b", "v1", second, null, null)));
    }
}
