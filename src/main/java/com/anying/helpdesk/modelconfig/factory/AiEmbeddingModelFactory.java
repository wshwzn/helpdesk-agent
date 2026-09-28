package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import org.springframework.ai.embedding.EmbeddingModel;

public interface AiEmbeddingModelFactory {

    /**
     * 返回该工厂支持的模型调用协议。
     */
    ModelProtocol protocol();

    /**
     * 根据已解密前的持久化配置创建 Embedding 模型客户端。
     */
    EmbeddingModel create(AiModelConfig config);
}
