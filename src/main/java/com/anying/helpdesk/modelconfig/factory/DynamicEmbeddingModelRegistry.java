package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import com.anying.helpdesk.modelconfig.exception.InvalidModelConfigException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DynamicEmbeddingModelRegistry {

    private final Map<ModelProtocol, AiEmbeddingModelFactory> factories;
    private final Map<Long, CachedEmbeddingModel> cache = new ConcurrentHashMap<>();

    /**
     * 按协议注册唯一的 Embedding 模型工厂。
     */
    public DynamicEmbeddingModelRegistry(List<AiEmbeddingModelFactory> factories) {
        Map<ModelProtocol, AiEmbeddingModelFactory> factoryMap = new EnumMap<>(ModelProtocol.class);
        for (AiEmbeddingModelFactory factory : factories) {
            if (factoryMap.put(factory.protocol(), factory) != null) {
                throw new IllegalStateException("duplicate embedding model factory: " + factory.protocol());
            }
        }
        this.factories = Map.copyOf(factoryMap);
    }

    /**
     * 按配置版本缓存 Embedding 模型，配置更新后自动重建。
     */
    public EmbeddingModel get(AiModelConfig config) {
        CachedEmbeddingModel cached = cache.compute(config.getId(), (id, existing) -> {
            if (existing != null && existing.version().equals(config.getVersion())) {
                return existing;
            }
            AiEmbeddingModelFactory factory = factories.get(config.getProtocol());
            if (factory == null) {
                throw new InvalidModelConfigException(
                        "unsupported model protocol: " + config.getProtocol()
                );
            }
            return new CachedEmbeddingModel(config.getVersion(), factory.create(config));
        });
        return cached.embeddingModel();
    }

    /**
     * 配置更新或禁用后清除对应运行时模型。
     */
    public void evict(Long configId) {
        cache.remove(configId);
    }

    private record CachedEmbeddingModel(Integer version, EmbeddingModel embeddingModel) {
    }
}
