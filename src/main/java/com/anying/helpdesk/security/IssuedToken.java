package com.anying.helpdesk.security;

import java.time.Instant;

public record IssuedToken(String value, Instant expiresAt) {
}
