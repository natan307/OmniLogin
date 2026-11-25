package dev.omnilogin.identity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers who OmniLogin let in (or wants to turn away), keyed by the UUID it handed to the real server.
 * A connection the registry does not know was not checked by OmniLogin and is refused (fail-closed).
 */
public final class IdentityRegistry {

    /** One identified connection. {@code denyMessage} is set when the player has to be kicked. */
    public record Entry(String name, Status status, String denyMessage) {
    }

    private record Held(Entry entry, long expires) {
    }

    private static final long TTL_MS = 120_000L;
    private final ConcurrentHashMap<UUID, Held> entries = new ConcurrentHashMap<>();

    public void put(UUID id, String name, Status status) {
        put(id, new Entry(name, status, null));
    }

    public void put(UUID id, Entry entry) {
        long now = System.currentTimeMillis();
        if (entries.size() > 2000) {
            entries.values().removeIf(h -> h.expires <= now);
        }

        entries.put(id, new Held(entry, now + TTL_MS));
    }

    /** Returns and removes the entry, or null when this connection was never identified. */
    public Entry consume(UUID id) {
        Held held = entries.remove(id);
        if (held == null || held.expires <= System.currentTimeMillis()) {
            return null;
        }

        return held.entry;
    }
}
