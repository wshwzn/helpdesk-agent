package com.anying.helpdesk.conversation;

import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.service.AgentToolCallService;
import com.anying.helpdesk.conversation.service.KnowledgeCitationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeCitationServiceTest {
    /**
     * 验证同名文件的不同文档版本不会被合并，同一文档的重复命中仍会去重。
     */
    @Test
    @DisplayName("引用按文档身份区分同名不同版本并保留业务快照")
    void distinguishesVersionsWithIdenticalFilenames() {
        AgentToolCall call = call("""
                {"status":"FOUND","knowledgeBase":"差旅库","query":"住宿标准","chunks":[
                  {"rank":1,"source":"差旅.md","chunkSequence":1,"content":"500 元","documentId":10,
                   "metadata":{"sourceKey":"travel","businessVersion":"v1","applicabilityScope":"ALL","validFrom":"2026-01-01T00:00:00"}},
                  {"rank":2,"source":"差旅.md","chunkSequence":1,"content":"600 元","documentId":11,
                   "metadata":{"sourceKey":"travel","businessVersion":"v2","applicabilityScope":"ALL","validFrom":"2026-10-01T00:00:00"}},
                  {"rank":3,"source":"差旅.md","chunkSequence":1,"content":"600 元","documentId":11}
                ]}
                """);
        var citations = service(call).listByAssistantMessageId(7L);
        assertThat(citations).hasSize(2);
        assertThat(citations).extracting(value -> value.documentId()).containsExactly(10L, 11L);
        assertThat(citations.get(1).metadata().businessVersion()).isEqualTo("v2");
    }

    /**
     * 验证历史工具 JSON 没有文档主键和版本字段时，原有消息仍可恢复引用。
     */
    @Test
    @DisplayName("旧工具记录缺少业务版本时仍能展示历史引用")
    void readsLegacyCitationSnapshots() {
        AgentToolCall call = call("""
                {"status":"FOUND","knowledgeBase":"差旅库","query":"住宿标准","chunks":[
                  {"rank":1,"source":"差旅.md","chunkSequence":1,"content":"旧片段"}
                ]}
                """);
        var citations = service(call).listByAssistantMessageId(7L);
        assertThat(citations).singleElement().satisfies(citation -> {
            assertThat(citation.content()).isEqualTo("旧片段");
            assertThat(citation.documentId()).isNull();
            assertThat(citation.metadata()).isNull();
        });
    }

    /**
     * 构造已持久化的检索工具结果。
     */
    private AgentToolCall call(String json) {
        AgentToolCall call = new AgentToolCall();
        call.setId(1L);
        call.setAssistantMessageId(7L);
        call.setToolName("search_knowledge_base");
        call.setResultJson(json);
        return call;
    }

    /**
     * 使用真实 JSON 解析器恢复引用，仅替换数据库读取边界。
     */
    private KnowledgeCitationService service(AgentToolCall call) {
        AgentToolCallService tools = mock(AgentToolCallService.class);
        when(tools.listSuccessfulByAssistantMessageIds(List.of(7L))).thenReturn(List.of(call));
        return new KnowledgeCitationService(tools, new ObjectMapper().findAndRegisterModules());
    }
}
