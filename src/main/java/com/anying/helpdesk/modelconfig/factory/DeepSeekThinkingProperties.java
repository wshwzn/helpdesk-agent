package com.anying.helpdesk.modelconfig.factory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** DeepSeek 请求级思考参数，仅从部署配置读取，不进入模型管理接口。 */
@Validated
@ConfigurationProperties("app.model-config.deepseek")
public record DeepSeekThinkingProperties(
        @DefaultValue("enabled") @NotBlank @Pattern(regexp = "enabled|disabled") String thinkingType,
        @DefaultValue("low") @NotBlank String reasoningEffort
) { }
