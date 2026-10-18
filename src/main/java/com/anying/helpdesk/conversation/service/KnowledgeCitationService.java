package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.service.AgentToolCallService;
import com.anying.helpdesk.agent.tool.KnowledgeSearchToolResult;
import com.anying.helpdesk.conversation.dto.KnowledgeCitationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeCitationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCitationService.class);
    private static final String KNOWLEDGE_SEARCH_TOOL = "search_knowledge_base";
    private static final String FOUND = "FOUND";

    private final AgentToolCallService toolCallService;
    private final ObjectMapper objectMapper;

    /**
     * 读取单条 AI 消息关联的知识库引用，供本轮回答完成时直接返回。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeCitationResponse> listByAssistantMessageId(Long assistantMessageId) {
        return listByAssistantMessageIds(List.of(assistantMessageId))
                .getOrDefault(assistantMessageId, List.of());
    }

    /**
     * 批量解析 AI 消息的知识检索工具结果，避免历史消息分页时逐条查询数据库。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<KnowledgeCitationResponse>> listByAssistantMessageIds(
            Collection<Long> assistantMessageIds
    ) {
        if (assistantMessageIds == null || assistantMessageIds.isEmpty()) {
            return Map.of();
        }
        List<AgentToolCall> toolCalls = toolCallService.listSuccessfulByAssistantMessageIds(
                assistantMessageIds
        );
        Map<Long, List<KnowledgeCitationResponse>> citationsByMessage = new LinkedHashMap<>();
        Map<Long, Set<CitationKey>> seenByMessage = new LinkedHashMap<>();
        for (AgentToolCall toolCall : toolCalls) {
            if (!KNOWLEDGE_SEARCH_TOOL.equals(toolCall.getToolName())) {
                continue;
            }
            appendToolResult(toolCall, citationsByMessage, seenByMessage);
        }
        citationsByMessage.replaceAll((messageId, citations) -> List.copyOf(citations));
        return Map.copyOf(citationsByMessage);
    }

    /**
     * 按文档主键和 Chunk 序号区分不同版本，旧记录回退到名称去重。
     */
    private void appendToolResult(
            AgentToolCall toolCall,
            Map<Long, List<KnowledgeCitationResponse>> citationsByMessage,
            Map<Long, Set<CitationKey>> seenByMessage
    ) {
        try {
            KnowledgeSearchToolResult result = objectMapper.readValue(
                    toolCall.getResultJson(),
                    KnowledgeSearchToolResult.class
            );
            if (!FOUND.equals(result.status())) {
                return;
            }
            List<KnowledgeCitationResponse> citations = citationsByMessage.computeIfAbsent(
                    toolCall.getAssistantMessageId(),
                    ignored -> new ArrayList<>()
            );
            Set<CitationKey> seen = seenByMessage.computeIfAbsent(
                    toolCall.getAssistantMessageId(),
                    ignored -> new LinkedHashSet<>()
            );
            result.chunks().forEach(chunk -> {
                CitationKey key = new CitationKey(
                        result.knowledgeBase(),
                        chunk.documentId() == null ? chunk.source() : null,
                        chunk.documentId(),
                        chunk.chunkSequence()
                );
                if (seen.add(key)) {
                    citations.add(new KnowledgeCitationResponse(
                            result.knowledgeBase(),
                            chunk.source(),
                            chunk.chunkSequence(),
                            chunk.content(),
                            chunk.documentId(),
                            chunk.metadata()
                    ));
                }
            });
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn(
                    "Failed to parse knowledge citation from tool call {} ({})",
                    toolCall.getId(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private record CitationKey(String knowledgeBaseName, String sourceName, Long documentId, int chunkSequence) {
    }
}
