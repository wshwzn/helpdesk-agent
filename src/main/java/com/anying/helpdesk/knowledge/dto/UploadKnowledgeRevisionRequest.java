package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Schema(description = "新版的业务版本与有效期；来源、范围固定继承旧版")
public record UploadKnowledgeRevisionRequest(
        @NotNull @Min(0) Integer predecessorVersion,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,39}") String businessVersion,
        @NotNull LocalDateTime validFrom,
        LocalDateTime validUntil) {

    /** 将业务时间统一到数据库微秒精度，保证重复请求比较稳定。 */
    public UploadKnowledgeRevisionRequest {
        validFrom = validFrom == null ? null : validFrom.truncatedTo(ChronoUnit.MICROS);
        validUntil = validUntil == null ? null : validUntil.truncatedTo(ChronoUnit.MICROS);
    }
}
