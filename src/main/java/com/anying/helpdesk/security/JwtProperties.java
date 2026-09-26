package com.anying.helpdesk.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        Duration accessTokenTtl
) {
}
