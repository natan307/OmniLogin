package dev.omnilogin;

import dev.omnilogin.auth.AuthManager;
import dev.omnilogin.identity.IdentityRegistry;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.UUID;

/**
 * Last line of defence: a connection that OmniLogin did not identify is refused (fail-closed), and verified premium
 * players are told to the password login so they skip it.
 */
public final class IdentityListener implements Listener {

    private final Settings settings;
    private final IdentityRegistry registry;
    private final AuthManager auth;

    /** {@code auth} may be null when the password login is disabled. */
    public IdentityListener(Settings settings, IdentityRegistry registry, AuthManager auth) {
        this.settings = settings;
        this.registry = registry;
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID id = event.getUniqueId();

        if ("backend".equals(settings.mode)) {
            // a proxy in front already checked the player; real (non offline) UUIDs belong to premium accounts
            if (auth != null && id.version() != 3) {
                auth.markPremium(id);
            }

            return;
        }

        // Floodgate gives Bedrock players UUIDs that start with zeros; only Floodgate (with its own key) can create them
        if (settings.geyserDetected && id.getMostSignificantBits() == 0L) {
            registry.consume(id);
            if (auth != null) {
                auth.markPremium(id);
            }

            return;
        }

        IdentityRegistry.Entry entry = registry.consume(id);
        if (entry == null || !entry.name().equalsIgnoreCase(event.getName())) {
            boolean loopback = event.getAddress() != null && event.getAddress().isLoopbackAddress();
            if (settings.geyserDetected && settings.geyserAllowUnregistered && loopback) {
                return;
            }

            if (settings.enforceIdentity) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, color(settings.msgUnverified));
            }

            return;
        }

        if (entry.denyMessage() != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, color(entry.denyMessage()));
            return;
        }

        if (auth != null && entry.status().isPremium()) {
            auth.markPremium(id);
        }
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
