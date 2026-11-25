package dev.omnilogin.proxy;

import dev.omnilogin.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/** Loads config.properties (UTF-8) for Velocity and BungeeCord. */
public final class ProxyConfig {

    private ProxyConfig() {
    }

    public static Settings load(Path dir, ClassLoader loader, Consumer<String> warn) {
        Settings s = new Settings();
        try {
            Path file = dir.resolve("config.properties");
            if (!Files.exists(file)) {
                Files.createDirectories(dir);
                try (InputStream in = loader.getResourceAsStream("proxy-config.properties")) {
                    if (in != null) {
                        Files.copy(in, file);
                    }
                }
            }

            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(reader);
            }

            s.allowCracked = bool(p, "login.allow-cracked", s.allowCracked);
            s.denyWhenLookupFails = bool(p, "login.deny-when-lookup-fails", s.denyWhenLookupFails);
            Set<String> names = new HashSet<>();
            for (String n : str(p, "login.bypass-names", "").split(",")) {
                if (!n.isBlank()) {
                    names.add(n.trim().toLowerCase(Locale.ROOT));
                }
            }

            s.bypassNames = Set.copyOf(names);
            s.relayEnabled = bool(p, "relay.enabled", s.relayEnabled);
            s.relayHost = str(p, "relay.host", s.relayHost);
            s.relayPort = (int) num(p, "relay.port", s.relayPort);
            s.timeoutMs = (int) num(p, "upstream.timeout-ms", s.timeoutMs);
            s.cacheRegisteredSeconds = num(p, "upstream.cache.registered-seconds", s.cacheRegisteredSeconds);
            s.cacheFreeSeconds = num(p, "upstream.cache.free-seconds", s.cacheFreeSeconds);
            s.microsoftSession = trim(str(p, "upstream.microsoft-session", s.microsoftSession));
            s.elybySession = trim(str(p, "upstream.elyby-session", s.elybySession));
            s.microsoftProfileApi = str(p, "upstream.microsoft-profile-api", s.microsoftProfileApi);
            s.elybyProfileApi = str(p, "upstream.elyby-profile-api", s.elybyProfileApi);
            s.msgReserved = str(p, "messages.reserved-name", s.msgReserved);
            s.msgCrackedDisabled = str(p, "messages.cracked-disabled", s.msgCrackedDisabled);
            s.msgLookupFailed = str(p, "messages.lookup-failed", s.msgLookupFailed);
            s.msgElyOnlyProxy = str(p, "messages.elyby-unsupported-proxy", s.msgElyOnlyProxy);
        } catch (IOException | RuntimeException failure) {
            warn.accept("Could not read config.properties, using defaults: " + failure);
        }

        return s;
    }

    private static String str(Properties p, String key, String def) {
        return p.getProperty(key, def).trim();
    }

    private static boolean bool(Properties p, String key, boolean def) {
        return Boolean.parseBoolean(p.getProperty(key, String.valueOf(def)).trim());
    }

    private static long num(Properties p, String key, long def) {
        try {
            return Long.parseLong(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException invalid) {
            return def;
        }
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
