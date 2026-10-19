package com.anying.helpdesk.knowledge.answer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class KnowledgeCitationCatalogTest {
    private final KnowledgeAnswerGuard guard = new KnowledgeAnswerGuard();

    /** 引文必须保留空格、换行、否定与完整适用条件，且偏移对应真实原文。 */
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    @DisplayName("按空行分段且逐字保留金额空格、条件和单换行")
    void preservesOriginalParagraph(String newline) {
        String rule = "仅限上海普通员工。" + newline + "每晚 600 元，超出部分不予报销。😀";
        var evidence = evidence("# 住宿标准" + newline + " \t" + newline + rule);
        var catalog = KnowledgeCitationCatalog.create(evidence);
        assertThat(catalog.sources().getFirst().paragraphs()).hasSize(2);
        var paragraph = catalog.sources().getFirst().paragraphs().get(1);
        assertThat(paragraph.text()).isEqualTo(rule);
        assertThat(evidence.getFirst().content().substring(paragraph.startOffset(), paragraph.endOffset())).isEqualTo(rule);
        var draft = guard.resolve(selection(catalog, "E1", "E1-P2"), catalog, evidence);
        assertThat(draft.claims().getFirst().references().getFirst().quote()).isEqualTo(rule);
    }

    /** 模型不能借用另一个证据内存在的片段，也不能编造编号。 */
    @ParameterizedTest
    @ValueSource(strings = {"E2-P1", "E1-P999", "", "e1-p1"})
    @DisplayName("跨证据、未知、空白或大小写变造的片段编号被拒绝")
    void rejectsWrongParagraphIdentity(String paragraphId) {
        var evidence = evidence("每晚 600 元。", "每晚 650 元。");
        var catalog = KnowledgeCitationCatalog.create(evidence);
        assertCitationFailure(() -> guard.resolve(selection(catalog, "E1", paragraphId), catalog, evidence));
    }

    /** 不同请求即使使用相同 E1-P1，也必须使用各自目录身份。 */
    @Test
    @DisplayName("上一轮片段选择不能应用到本轮目录")
    void rejectsPreviousRound() {
        var evidence = evidence("每晚 600 元。");
        var previous = KnowledgeCitationCatalog.create(evidence);
        var current = KnowledgeCitationCatalog.create(evidence);
        assertThat(previous.citationSetId()).isNotEqualTo(current.citationSetId());
        assertCitationFailure(() -> guard.resolve(selection(previous, "E1", "E1-P1"), current, evidence));
    }

    /** 超长段落不能为了符合引文限制被切断否定条件，短标题也不能充当实质依据。 */
    @ParameterizedTest
    @ValueSource(strings = {"短", "LONG"})
    @DisplayName("过短和超过一千五百字的段落只保留上下文而不可引用")
    void rejectsUnselectableParagraph(String value) {
        String content = value.equals("LONG") ? "条".repeat(1500) + "不适用" : value;
        var evidence = evidence(content);
        var catalog = KnowledgeCitationCatalog.create(evidence);
        assertThat(catalog.sources().getFirst().paragraphs()).singleElement().satisfies(paragraph -> {
            assertThat(paragraph.text()).isEqualTo(content);
            assertThat(paragraph.selectable()).isFalse();
        });
        assertCitationFailure(() -> guard.resolve(selection(catalog, "E1", "E1-P1"), catalog, evidence));
    }

    /** 正好达到引用上限的完整段落仍可被选择。 */
    @Test
    @DisplayName("一千五百字边界段落仍按原文返回")
    void acceptsMaximumLength() {
        var evidence = evidence("条".repeat(1500));
        var catalog = KnowledgeCitationCatalog.create(evidence);
        assertThat(guard.resolve(selection(catalog, "E1", "E1-P1"), catalog, evidence)
                .claims().getFirst().references().getFirst().quote()).hasSize(1500);
    }

    /** 两个来源的相同文本不能合并身份，冲突和来源审计依赖各自文档身份。 */
    @Test
    @DisplayName("相同原文在不同来源中保持独立片段身份")
    void keepsDuplicateTextSourcesSeparate() {
        var evidence = evidence("每晚 600 元。", "每晚 600 元。");
        var catalog = KnowledgeCitationCatalog.create(evidence);
        assertThat(guard.resolve(selection(catalog, "E2", "E2-P1"), catalog, evidence)
                .claims().getFirst().references().getFirst().evidenceId()).isEqualTo("E2");
    }

    /** 目录和证据快照被错误混用时拒绝，而非按另一份内容盲取偏移。 */
    @Test
    @DisplayName("目录偏移必须仍指向匹配的原文快照")
    void rejectsDifferentOriginalSnapshot() {
        var catalog = KnowledgeCitationCatalog.create(evidence("每晚 600 元。"));
        assertCitationFailure(() -> guard.resolve(selection(catalog, "E1", "E1-P1"), catalog, evidence("每晚 900 元。")));
    }

    /** 缺字段和空引用不能绕过结构校验，也不能以空指针异常掩盖精确错误码。 */
    @Test
    @DisplayName("空选择、空列表和空引用均返回固定错误码")
    void rejectsIncompleteSelection() {
        var evidence = evidence("每晚 600 元。");
        var catalog = KnowledgeCitationCatalog.create(evidence);
        assertThatThrownBy(() -> guard.resolve(null, catalog, evidence)).hasMessage("missing answer selection");
        assertThatThrownBy(() -> guard.resolve(new KnowledgeAnswerSelection(catalog.citationSetId(),
                KnowledgeAnswerDraft.Status.ANSWERABLE, null, List.of(), List.of()), catalog, evidence)).hasMessage("invalid claims");
        var bad = new KnowledgeAnswerSelection(catalog.citationSetId(), KnowledgeAnswerDraft.Status.ANSWERABLE,
                List.of(new KnowledgeAnswerSelection.Claim("每晚600元", Arrays.asList((KnowledgeAnswerSelection.Reference) null))),
                List.of(), List.of());
        assertCitationFailure(() -> guard.resolve(bad, catalog, evidence));
    }

    /** 构造仅用于边界测试的本轮片段选择。 */
    private KnowledgeAnswerSelection selection(KnowledgeCitationCatalog catalog, String evidenceId, String paragraphId) {
        return new KnowledgeAnswerSelection(catalog.citationSetId(), KnowledgeAnswerDraft.Status.ANSWERABLE,
                List.of(new KnowledgeAnswerSelection.Claim("额度为600元", List.of(
                        new KnowledgeAnswerSelection.Reference(evidenceId, paragraphId)))), List.of(), List.of());
    }

    /** 构造独立文档的检索快照，不依赖外部检索和真实模型。 */
    private List<KnowledgeAnswerReport.Evidence> evidence(String... contents) {
        return java.util.stream.IntStream.range(0, contents.length).mapToObj(index ->
                new KnowledgeAnswerReport.Evidence("E" + (index + 1), (long) index + 1, 1, "制度.md", contents[index], null)).toList();
    }

    /** 断言失败属于引用边界，而非复核否决或外部服务异常。 */
    private void assertCitationFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(KnowledgeAnswerFailure.class,
                failure -> assertThat(failure.errorCode()).isEqualTo("KNOWLEDGE_CITATION_INVALID"));
    }
}
