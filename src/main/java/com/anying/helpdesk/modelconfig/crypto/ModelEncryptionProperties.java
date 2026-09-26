package com.anying.helpdesk.modelconfig.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.model-config")
public record ModelEncryptionProperties(String encryptionKey) {
}
