package com.anying.helpdesk.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage")
public class ObjectStorageProperties {

    private String provider = "local";
    private String localRootDirectory = "./data/objects";
    private String endpoint;
    private String bucket;
    private String accessKeyId;
    private String accessKeySecret;
}
