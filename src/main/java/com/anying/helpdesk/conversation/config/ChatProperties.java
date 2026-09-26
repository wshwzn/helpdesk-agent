package com.anying.helpdesk.conversation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.chat")
public record ChatProperties(
        @Min(1) @Max(100) int historyLimit
) {
}
