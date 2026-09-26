package com.anying.helpdesk.modelconfig.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ai_model_configs")
public class AiModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private ModelProviderType providerType;
    private String baseUrl;
    private String modelName;
    private String apiKeyCiphertext;
    private String apiKeyHint;
    private Integer encryptionKeyVersion;
    private Boolean supportsStreaming;
    private Boolean supportsTools;
    private Boolean enabled;
    private Boolean isDefault;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
