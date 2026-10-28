package com.anying.helpdesk.config;

import com.anying.helpdesk.modelconfig.crypto.AesGcmSecretEncryptor;
import com.anying.helpdesk.modelconfig.crypto.ModelEncryptionProperties;
import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.factory.DeepSeekThinkingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties({ModelEncryptionProperties.class, DeepSeekThinkingProperties.class})
public class ModelConfigConfiguration {

    @Bean
    public SecretEncryptor modelConfigSecretEncryptor(ModelEncryptionProperties properties) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.encryptionKey());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("app.model-config.encryption-key must be valid Base64", exception);
        }
        if (key.length != 32) {
            throw new IllegalStateException("app.model-config.encryption-key must decode to exactly 32 bytes");
        }
        SecretKey secretKey = new SecretKeySpec(key, "AES");
        return new AesGcmSecretEncryptor(secretKey);
    }
}
