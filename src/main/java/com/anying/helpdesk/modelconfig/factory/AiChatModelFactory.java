package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import org.springframework.ai.chat.model.ChatModel;

public interface AiChatModelFactory {

    ModelProtocol protocol();

    ChatModel create(AiModelConfig config);
}
