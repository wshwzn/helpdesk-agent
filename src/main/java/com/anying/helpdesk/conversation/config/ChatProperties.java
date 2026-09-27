package com.anying.helpdesk.conversation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("app.chat")
public record ChatProperties(
        @Min(1) @Max(100) int historyLimit,
        @NotNull @DefaultValue("5m") Duration streamTimeout,
        @DefaultValue("false") boolean logModelContent
) {
}
