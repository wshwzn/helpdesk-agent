package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProviderType;
import org.springframework.ai.chat.model.ChatModel;

public interface AiChatModelFactory {

    ModelProviderType providerType();

    ChatModel create(AiModelConfig config);
}
