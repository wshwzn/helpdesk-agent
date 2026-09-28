package com.anying.helpdesk.modelconfig.service;

import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import com.anying.helpdesk.modelconfig.domain.ModelType;
import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import com.anying.helpdesk.modelconfig.dto.CreateModelConfigRequest;
import com.anying.helpdesk.modelconfig.dto.ModelConfigResponse;
import com.anying.helpdesk.modelconfig.dto.ModelConnectionTestResponse;
import com.anying.helpdesk.modelconfig.dto.UpdateModelConfigRequest;
import com.anying.helpdesk.modelconfig.exception.InvalidModelConfigException;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.anying.helpdesk.modelconfig.exception.ModelConfigNotFoundException;
import com.anying.helpdesk.modelconfig.factory.DynamicChatModelRegistry;
import com.anying.helpdesk.modelconfig.factory.DynamicEmbeddingModelRegistry;
import com.anying.helpdesk.modelconfig.mapper.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfigService.class);

    private final AiModelConfigMapper modelConfigMapper;
    private final SecretEncryptor secretEncryptor;
    private final DynamicChatModelRegistry chatModelRegistry;
    private final DynamicEmbeddingModelRegistry embeddingModelRegistry;
    private final ModelConnectionTester modelConnectionTester;

    @Transactional
    public ModelConfigResponse create(CreateModelConfigRequest request, Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        String apiKey = request.apiKey().trim();
        ModelType modelType = request.modelType() == null ? ModelType.CHAT : request.modelType();
        validateCapabilities(
                modelType,
                request.supportsStreaming(),
                request.supportsTools(),
                request.embeddingDimensions()
        );
        AiModelConfig config = new AiModelConfig();
        config.setName(request.name().trim());
        config.setModelType(modelType);
        config.setProtocol(ModelProtocol.OPENAI_COMPATIBLE);
        config.setVendor(request.vendor());
        config.setBaseUrl(resolveBaseUrl(request.vendor(), request.baseUrl()));
        config.setModelName(request.modelName().trim());
        config.setApiKeyCiphertext(secretEncryptor.encrypt(apiKey));
        config.setApiKeyHint(maskApiKey(apiKey));
        config.setEncryptionKeyVersion(1);
        config.setSupportsStreaming(request.supportsStreaming());
        config.setSupportsTools(request.supportsTools());
        config.setEmbeddingDimensions(request.embeddingDimensions());
        config.setEnabled(request.enabled());
        config.setIsDefault(false);
        config.setCreatedBy(adminId);
        config.setUpdatedBy(adminId);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        try {
            modelConfigMapper.insert(config);
        } catch (DuplicateKeyException exception) {
            throw new ModelConfigConflictException("model configuration name already exists");
        }
        return ModelConfigResponse.from(config);
    }

    @Transactional(readOnly = true)
    public List<ModelConfigResponse> list() {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<AiModelConfig>()
                        .orderByDesc(AiModelConfig::getIsDefault)
                        .orderByDesc(AiModelConfig::getUpdatedAt))
                .stream()
                .map(ModelConfigResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ModelConfigResponse getById(Long id) {
        return ModelConfigResponse.from(requireConfig(id));
    }

    @Transactional
    public ModelConfigResponse update(Long id, UpdateModelConfigRequest request, Long adminId) {
        AiModelConfig config = requireConfig(id);
        ModelType modelType = request.modelType() == null ? config.getModelType() : request.modelType();
        Integer embeddingDimensions = request.embeddingDimensions();
        if (modelType == ModelType.EMBEDDING
                && config.getModelType() == ModelType.EMBEDDING
                && embeddingDimensions == null) {
            embeddingDimensions = config.getEmbeddingDimensions();
        }
        validateCapabilities(
                modelType,
                request.supportsStreaming(),
                request.supportsTools(),
                embeddingDimensions
        );
        if (config.getModelType() != modelType && Boolean.TRUE.equals(config.getIsDefault())) {
            config.setIsDefault(false);
        }
        config.setName(request.name().trim());
        config.setModelType(modelType);
        config.setProtocol(ModelProtocol.OPENAI_COMPATIBLE);
        config.setVendor(request.vendor());
        config.setBaseUrl(resolveBaseUrl(request.vendor(), request.baseUrl()));
        config.setModelName(request.modelName().trim());
        if (request.apiKey() != null) {
            String apiKey = request.apiKey().trim();
            if (apiKey.isEmpty()) {
                throw new InvalidModelConfigException("apiKey must not be blank when provided");
            }
            config.setApiKeyCiphertext(secretEncryptor.encrypt(apiKey));
            config.setApiKeyHint(maskApiKey(apiKey));
        }
        config.setSupportsStreaming(request.supportsStreaming());
        config.setSupportsTools(request.supportsTools());
        config.setEmbeddingDimensions(embeddingDimensions);
        config.setUpdatedBy(adminId);
        config.setUpdatedAt(LocalDateTime.now());
        try {
            updateWithOptimisticLock(config);
        } catch (DuplicateKeyException exception) {
            throw new ModelConfigConflictException("model configuration name already exists");
        }
        evictRuntimeModels(id);
        return ModelConfigResponse.from(config);
    }

    @Transactional
    public ModelConfigResponse enable(Long id, Long adminId) {
        AiModelConfig config = requireConfig(id);
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            config.setEnabled(true);
            touchAndUpdate(config, adminId);
        }
        return ModelConfigResponse.from(config);
    }

    @Transactional
    public ModelConfigResponse disable(Long id, Long adminId) {
        AiModelConfig config = requireConfig(id);
        if (Boolean.TRUE.equals(config.getIsDefault())) {
            throw new ModelConfigConflictException("default model configuration cannot be disabled");
        }
        if (Boolean.TRUE.equals(config.getEnabled())) {
            config.setEnabled(false);
            touchAndUpdate(config, adminId);
            evictRuntimeModels(id);
        }
        return ModelConfigResponse.from(config);
    }

    @Transactional
    public ModelConfigResponse setDefault(Long id, Long adminId) {
        AiModelConfig config = requireConfig(id);
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new ModelConfigConflictException("disabled model configuration cannot be the default");
        }
        LocalDateTime now = LocalDateTime.now();
        modelConfigMapper.update(null, new LambdaUpdateWrapper<AiModelConfig>()
                .eq(AiModelConfig::getModelType, config.getModelType())
                .eq(AiModelConfig::getIsDefault, true)
                .set(AiModelConfig::getIsDefault, false)
                .set(AiModelConfig::getUpdatedBy, adminId)
                .set(AiModelConfig::getUpdatedAt, now));
        config.setIsDefault(true);
        config.setUpdatedBy(adminId);
        config.setUpdatedAt(now);
        updateWithOptimisticLock(config);
        return ModelConfigResponse.from(config);
    }

    @Transactional(readOnly = true)
    public ModelConnectionTestResponse testConnection(Long id) {
        AiModelConfig config = requireConfig(id);
        evictRuntimeModels(id);
        try {
            modelConnectionTester.test(config);
            return new ModelConnectionTestResponse(true, "connection test succeeded");
        } catch (RuntimeException exception) {
            evictRuntimeModels(id);
            log.warn(
                    "Model connection test failed for config {} ({})",
                    id,
                    exception.getClass().getSimpleName()
            );
            return new ModelConnectionTestResponse(
                    false,
                    "connection test failed; verify the URL, API key, and model name"
            );
        }
    }

    private AiModelConfig requireConfig(Long id) {
        AiModelConfig config = modelConfigMapper.selectById(id);
        if (config == null) {
            throw new ModelConfigNotFoundException(id);
        }
        return config;
    }

    private void touchAndUpdate(AiModelConfig config, Long adminId) {
        config.setUpdatedBy(adminId);
        config.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(config);
    }

    private void updateWithOptimisticLock(AiModelConfig config) {
        if (modelConfigMapper.updateById(config) == 0) {
            throw new ModelConfigConflictException("model configuration was updated concurrently");
        }
    }

    private String resolveBaseUrl(ModelVendor vendor, String requestedBaseUrl) {
        if (!vendor.isCustomBaseUrlRequired()) {
            if (requestedBaseUrl != null && !requestedBaseUrl.isBlank()) {
                throw new InvalidModelConfigException("baseUrl must be omitted for a preset vendor");
            }
            return vendor.getPresetBaseUrl();
        }
        if (requestedBaseUrl == null || requestedBaseUrl.isBlank()) {
            throw new InvalidModelConfigException("baseUrl is required for the CUSTOM vendor");
        }
        return normalizeBaseUrl(requestedBaseUrl);
    }

    private String normalizeBaseUrl(String value) {
        try {
            URI uri = new URI(value.trim()).normalize();
            if (uri.getScheme() == null
                    || (!uri.getScheme().equalsIgnoreCase("https") && !uri.getScheme().equalsIgnoreCase("http"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new InvalidModelConfigException("baseUrl must be a valid HTTP or HTTPS URL");
            }
            String normalized = uri.toString();
            normalized = normalized.endsWith("/")
                    ? normalized.substring(0, normalized.length() - 1)
                    : normalized;
            if (normalized.endsWith("/chat/completions")) {
                throw new InvalidModelConfigException(
                        "baseUrl must be an SDK base URL and must not include /chat/completions"
                );
            }
            if (normalized.endsWith("/embeddings")) {
                throw new InvalidModelConfigException(
                        "baseUrl must be an SDK base URL and must not include /embeddings"
                );
            }
            return normalized;
        } catch (URISyntaxException exception) {
            throw new InvalidModelConfigException("baseUrl must be a valid HTTP or HTTPS URL");
        }
    }

    private String maskApiKey(String apiKey) {
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 校验聊天与向量模型各自适用的能力字段，避免产生含义矛盾的配置。
     */
    private void validateCapabilities(
            ModelType modelType,
            Boolean supportsStreaming,
            Boolean supportsTools,
            Integer embeddingDimensions
    ) {
        if (modelType == ModelType.CHAT) {
            if (embeddingDimensions != null) {
                throw new InvalidModelConfigException("embeddingDimensions must be omitted for CHAT models");
            }
            return;
        }
        if (Boolean.TRUE.equals(supportsStreaming) || Boolean.TRUE.equals(supportsTools)) {
            throw new InvalidModelConfigException(
                    "EMBEDDING models must set supportsStreaming and supportsTools to false"
            );
        }
        if (embeddingDimensions == null) {
            throw new InvalidModelConfigException("embeddingDimensions is required for EMBEDDING models");
        }
    }

    /**
     * 同时清除聊天和向量模型缓存，兼容配置用途发生变化。
     */
    private void evictRuntimeModels(Long configId) {
        chatModelRegistry.evict(configId);
        embeddingModelRegistry.evict(configId);
    }
}
