package com.anying.helpdesk.knowledge.processing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.knowledge.processing")
public class KnowledgeProcessingProperties {

    @Min(1000)
    @Max(10_000_000)
    private int maxExtractedCharacters = 2_000_000;

    @Min(100)
    @Max(10_000)
    private int chunkSize = 1000;

    @Min(0)
    @Max(5000)
    private int chunkOverlap = 150;
}
