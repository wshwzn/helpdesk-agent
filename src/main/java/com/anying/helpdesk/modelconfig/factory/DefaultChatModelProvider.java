package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelType;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.anying.helpdesk.modelconfig.mapper.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultChatModelProvider {

    private final AiModelConfigMapper modelConfigMapper;
    private final DynamicChatModelRegistry chatModelRegistry;

    public ResolvedChatModel get() {
        List<AiModelConfig> configs = modelConfigMapper.selectList(new LambdaQueryWrapper<AiModelConfig>()
                .eq(AiModelConfig::getModelType, ModelType.CHAT)
                .eq(AiModelConfig::getEnabled, true)
                .eq(AiModelConfig::getIsDefault, true));
        if (configs.isEmpty()) {
            throw new ModelConfigConflictException("no enabled default model configuration is available");
        }
        if (configs.size() > 1) {
            throw new ModelConfigConflictException("multiple default model configurations are enabled");
        }
        AiModelConfig config = configs.getFirst();
        return new ResolvedChatModel(
                config.getId(),
                config.getModelName(),
                Boolean.TRUE.equals(config.getSupportsStreaming()),
                chatModelRegistry.get(config)
        );
    }
}
