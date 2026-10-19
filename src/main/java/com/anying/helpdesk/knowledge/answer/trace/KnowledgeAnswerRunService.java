package com.anying.helpdesk.knowledge.answer.trace;

import com.anying.helpdesk.conversation.service.PreparedChatExchange;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class KnowledgeAnswerRunService {
    private final KnowledgeAnswerRunStore store;
    private final Clock knowledgeClock;

    /** 为已通过会话归属校验并落库的知识消息启动诊断，不接受客户端指定的运行身份。 */
    public KnowledgeAnswerTrace start(PreparedChatExchange exchange, ResolvedChatModel model) {
        var message = exchange.userMessage();
        var trace = new KnowledgeAnswerTrace(exchange.knowledgeRunId(), exchange.assistantMessageId(),
                message.getConversationId(), message.getContent(), message.getApplicabilityScope(), model,
                knowledgeClock, store::save);
        trace.start();
        return trace;
    }
}
