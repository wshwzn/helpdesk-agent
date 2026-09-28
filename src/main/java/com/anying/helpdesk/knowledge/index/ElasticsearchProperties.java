package com.anying.helpdesk.knowledge.index;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.elasticsearch")
public class ElasticsearchProperties {

    @NotBlank
    private String uri = "http://localhost:9200";
    private String username = "";
    private String password = "";
    private String caCertPath = "";
    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9_-]*")
    private String indexName = "helpdesk_knowledge_chunks_v1";
    private Duration requestTimeout = Duration.ofSeconds(30);
}
