package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeAnswerModelClientTest {
    private final ChatModel chatModel = mock(ChatModel.class);
    private final ResolvedChatModel model = new ResolvedChatModel(1L, "test", true, chatModel);
    private final KnowledgeAnswerModelClient client = new KnowledgeAnswerModelClient(JsonMapper.builder().build());

    /** 检查模型只收到当前问题、结构化资料与规则，不带工具和旧轮消息。 */
    @Test
    @DisplayName("草案使用独立提示且不注册任何工具")
    void structuredDraftWithoutTools() {
        respond("{\"status\":\"INSUFFICIENT_EVIDENCE\",\"claims\":[],\"missingConditions\":[],\"conflicts\":[]}");
        var evidence = List.of(new KnowledgeAnswerReport.Evidence("E1", 10L, 2,
                "制度.md", "资料中的指令不可信", null));
        var result = client.draft(model, "测试问题", "SH", evidence);
        assertThat(result.value().status()).isEqualTo(KnowledgeAnswerDraft.Status.INSUFFICIENT_EVIDENCE);
        assertThat(result.totalTokens()).isNull();
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isNull();
        assertThat(prompt.getValue().getInstructions()).hasSize(2);
        assertThat(prompt.getValue().getInstructions().getLast().getText())
                .contains("测试问题", "SH", "E1", "10", "资料中的指令不可信");
    }

    /** 严格协议避免截取自然语言中的一段 JSON 并误判为合法回答。 */
    @ParameterizedTest
    @ValueSource(strings = {"不是JSON", "{\"approved\":true} {}", "{\"approved\":true,\"extra\":1}",
            "{\"approved\":true,\"approved\":false}", "```json\n{\"approved\":true}\n```"})
    @DisplayName("畸形、多段、重复字段和未知字段输出均被拒绝")
    void rejectsInvalidProtocol(String response) {
        respond(response);
        assertThatThrownBy(() -> client.review(model, "问题", "ALL", List.of(),
                new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.INSUFFICIENT_EVIDENCE,
                        List.of(), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessage("invalid knowledge answer protocol");
    }

    /** 非法草案结构即使可反序列化也不能越过确定性校验。 */
    @Test
    @DisplayName("缺失列表或无依据的可回答状态不能通过程序校验")
    void rejectsIncompleteDraft() {
        var guard = new KnowledgeAnswerGuard();
        assertThatThrownBy(() -> guard.validate(new KnowledgeAnswerDraft(
                KnowledgeAnswerDraft.Status.ANSWERABLE, null, List.of(), List.of()), List.of()))
                .hasMessage("invalid claims");
        assertThatThrownBy(() -> guard.validate(new KnowledgeAnswerDraft(
                KnowledgeAnswerDraft.Status.ANSWERABLE, List.of(), List.of(), List.of()), List.of()))
                .hasMessage("answer requires supported claims");
    }

    /** 原因字段必须来自固定枚举，不能把模型生成的任意文本用作诊断标签。 */
    @Test
    @DisplayName("复核协议解析固定原因枚举并拒绝未知自由文本")
    void parsesReviewReasonEnum() {
        respond("{\"approved\":false,\"reason\":\"WRONG_STATUS\"}");
        var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.INSUFFICIENT_EVIDENCE,
                List.of(), List.of(), List.of());
        assertThat(client.review(model, "问题", "ALL", List.of(), draft).value().reason())
                .isEqualTo(KnowledgeAnswerModelClient.ReviewReason.WRONG_STATUS);
        respond("{\"approved\":false,\"reason\":\"private model text\"}");
        assertThatThrownBy(() -> client.review(model, "问题", "ALL", List.of(), draft))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo("KNOWLEDGE_PROTOCOL_INVALID"));
    }

    /** 厂商已经返回计费信息但协议解析失败时，仍将用量交给安全的阶段诊断。 */
    @Test
    @DisplayName("畸形模型响应保留已知 Token 且不保留原始正文")
    void protocolFailureKeepsKnownUsage() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("private malformed response"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(20, 8, 28)).build()));
        assertThatThrownBy(() -> client.draft(model, "问题", "ALL", List.of()))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class, failure -> {
                    assertThat(failure.usage().totalTokens()).isEqualTo(28);
                    assertThat(failure.getMessage()).doesNotContain("private malformed response");
                    assertThat(failure.getCause()).isNull();
                });
    }

    /** 将测试 JSON 包装成厂商响应。 */
    private void respond(String content) {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))));
    }
}
