package com.anying.helpdesk.knowledge.source;

import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeDocumentMetadataRequest;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
import jakarta.validation.Validator;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public record SourceBusinessMetadata(String sourceKey, String businessVersion, String scope,
                                     LocalDateTime validFrom, LocalDateTime validUntil) {
    /** 从 OSS 自定义元数据读取显式业务规则，缺失或非法字段不猜测、不采用文件修改时间。 */
    public static SourceBusinessMetadata parse(Map<String, String> values, Validator validator) {
        try {
            var from = LocalDateTime.parse(values.get("kb-valid-from")).truncatedTo(ChronoUnit.MICROS);
            var until = values.get("kb-valid-until") == null ? null
                    : LocalDateTime.parse(values.get("kb-valid-until")).truncatedTo(ChronoUnit.MICROS);
            var request = new UpdateKnowledgeDocumentMetadataRequest(0, values.get("kb-source-key"),
                    values.get("kb-business-version"), values.get("kb-scope"), from, until);
            if (!validator.validate(request).isEmpty() || (until != null && !until.isAfter(from))) {
                throw new IllegalArgumentException();
            }
            return new SourceBusinessMetadata(request.sourceKey(), request.businessVersion(),
                    request.applicabilityScope(), from, until);
        } catch (RuntimeException exception) {
            throw new InvalidKnowledgeDocumentException("OSS business metadata is missing or invalid");
        }
    }
}
