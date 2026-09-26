package com.anying.helpdesk.modelconfig.service;

import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.dto.CreateModelConfigRequest;
import com.anying.helpdesk.modelconfig.dto.ModelConfigResponse;
import com.anying.helpdesk.modelconfig.dto.ModelConnectionTestResponse;
import com.anying.helpdesk.modelconfig.dto.UpdateModelConfigRequest;
import com.anying.helpdesk.modelconfig.exception.InvalidModelConfigException;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.anying.helpdesk.modelconfig.exception.ModelConfigNotFoundException;
import com.anying.helpdesk.modelconfig.factory.DynamicChatModelRegistry;
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
    private final ModelConnectionTester modelConnectionTester;

    @Transactional
    public ModelConfigResponse create(CreateModelConfigRequest request, Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        String apiKey = request.apiKey().trim();
        AiModelConfig config = new AiModelConfig();
        config.setName(request.name().trim());
        config.setProviderType(request.providerType());
        config.setBaseUrl(normalizeBaseUrl(request.baseUrl()));
        config.setModelName(request.modelName().trim());
        config.setApiKeyCiphertext(secretEncryptor.encrypt(apiKey));
        config.setApiKeyHint(maskApiKey(apiKey));
        config.setEncryptionKeyVersion(1);
        config.setSupportsStreaming(request.supportsStreaming());
        config.setSupportsTools(request.supportsTools());
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
        config.setName(request.name().trim());
        config.setProviderType(request.providerType());
        config.setBaseUrl(normalizeBaseUrl(request.baseUrl()));
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
        config.setUpdatedBy(adminId);
        config.setUpdatedAt(LocalDateTime.now());
        try {
            updateWithOptimisticLock(config);
        } catch (DuplicateKeyException exception) {
            throw new ModelConfigConflictException("model configuration name already exists");
        }
        chatModelRegistry.evict(id);
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
            chatModelRegistry.evict(id);
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
        chatModelRegistry.evict(id);
        try {
            modelConnectionTester.test(config);
            return new ModelConnectionTestResponse(true, "connection test succeeded");
        } catch (RuntimeException exception) {
            chatModelRegistry.evict(id);
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
            return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
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
}
