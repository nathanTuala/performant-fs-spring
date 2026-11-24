package com.performantfs.auth;

import java.util.UUID;

/** The signed-in user, as seen by controllers via {@code @AuthenticationPrincipal}. */
public record AuthUser(UUID id, String email, UUID rootId) {}
