package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelType;
import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.anying.helpdesk.modelconfig.mapper.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultEmbeddingModelProvider {

    private final AiModelConfigMapper modelConfigMapper;
    private final DynamicEmbeddingModelRegistry embeddingModelRegistry;

    /**
     * 解析唯一启用的默认 Embedding 模型及其固定向量维度。
     */
    public ResolvedEmbeddingModel get() {
        List<AiModelConfig> configs = modelConfigMapper.selectList(new LambdaQueryWrapper<AiModelConfig>()
                .eq(AiModelConfig::getModelType, ModelType.EMBEDDING)
                .eq(AiModelConfig::getEnabled, true)
                .eq(AiModelConfig::getIsDefault, true));
        if (configs.isEmpty()) {
            throw new ModelConfigConflictException("no enabled default embedding model configuration is available");
        }
        if (configs.size() > 1) {
            throw new ModelConfigConflictException("multiple default embedding model configurations are enabled");
        }
        AiModelConfig config = configs.getFirst();
        if (config.getVendor() != ModelVendor.CUSTOM) {
            throw new ModelConfigConflictException("default embedding model must use the CUSTOM vendor");
        }
        if (config.getEmbeddingDimensions() == null) {
            throw new ModelConfigConflictException("default embedding model dimensions are missing");
        }
        return new ResolvedEmbeddingModel(
                config.getId(),
                config.getModelName(),
                config.getEmbeddingDimensions(),
                embeddingModelRegistry.get(config)
        );
    }
}
