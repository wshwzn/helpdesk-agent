package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import com.anying.helpdesk.modelconfig.exception.InvalidModelConfigException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DynamicChatModelRegistry {

    private final Map<ModelProtocol, AiChatModelFactory> factories;
    private final Map<Long, CachedChatModel> cache = new ConcurrentHashMap<>();

    public DynamicChatModelRegistry(List<AiChatModelFactory> factories) {
        Map<ModelProtocol, AiChatModelFactory> factoryMap = new EnumMap<>(ModelProtocol.class);
        for (AiChatModelFactory factory : factories) {
            if (factoryMap.put(factory.protocol(), factory) != null) {
                throw new IllegalStateException("duplicate chat model factory: " + factory.protocol());
            }
        }
        this.factories = Map.copyOf(factoryMap);
    }

    public ChatModel get(AiModelConfig config) {
        CachedChatModel cached = cache.compute(config.getId(), (id, existing) -> {
            if (existing != null && existing.version().equals(config.getVersion())) {
                return existing;
            }
            AiChatModelFactory factory = factories.get(config.getProtocol());
            if (factory == null) {
                throw new InvalidModelConfigException(
                        "unsupported model protocol: " + config.getProtocol()
                );
            }
            return new CachedChatModel(config.getVersion(), factory.create(config));
        });
        return cached.chatModel();
    }

    public void evict(Long configId) {
        cache.remove(configId);
    }

    private record CachedChatModel(Integer version, ChatModel chatModel) {
    }
}
