package com.anying.helpdesk.knowledge.index;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties("app.knowledge.index-tasks")
public class KnowledgeIndexTaskProperties {
    private boolean enabled = true;
    @Min(1)
    private int concurrency = 3;
    @Min(1)
    private int maxAttempts = 3;
    @Min(30)
    private int leaseSeconds = 120;
    @Min(1)
    private int retryDelaySeconds = 30;
}
