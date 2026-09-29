package com.anying.helpdesk.conversation.config;

import jakarta.validation.constraints.AssertTrue;
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
        @NotNull @DefaultValue("10m") Duration pendingTimeout,
        @NotNull @DefaultValue("1m") Duration pendingRecoveryInterval,
        @DefaultValue("false") boolean logModelProtocol
) {

    /**
     * 保证回收阈值晚于正常流式请求上限，避免将仍在生成的回答误判为中断。
     */
    @AssertTrue(message = "app.chat timeouts must be positive and pending-timeout must exceed stream-timeout")
    public boolean isPendingTimeoutValid() {
        return !streamTimeout.isZero()
                && !streamTimeout.isNegative()
                && pendingTimeout.compareTo(streamTimeout) > 0;
    }

    /**
     * 保证后台回收间隔为正数，避免调度器因无效配置无法启动。
     */
    @AssertTrue(message = "app.chat.pending-recovery-interval must be positive")
    public boolean isPendingRecoveryIntervalValid() {
        return !pendingRecoveryInterval.isZero() && !pendingRecoveryInterval.isNegative();
    }
}
