package dev.omnilogin.identity;

import java.util.UUID;

/** A profile confirmed by a session server. {@code properties} is the raw JSON array (skin textures). */
public record Verification(UUID id, String name, String properties, Status status) {
}
