package dev.omnilogin;

import java.util.Set;

/** Plain settings holder (filled from config.yml by the plugin). */
public final class Settings {

    public volatile boolean allowCracked = true;
    public volatile boolean denyWhenLookupFails = true;
    public volatile Set<String> bypassNames = Set.of();
    public volatile boolean usePremiumUuid = false;
    public volatile boolean enforceIdentity = true;
    /** Log every step of every login (for troubleshooting). */
    public volatile boolean debug = false;
    /** A login step (name check, session check) that takes longer than this ends with a clear kick message. */
    public volatile long stepTimeoutMs = 15000L;
    /** Set at startup: true when spigot.yml has settings.bungeecord: true, so the real UUID and skin can be passed on. */
    public volatile boolean forwardIdentity = false;
    /** Set at startup: Geyser or Floodgate is installed, so Bedrock players have to be let through. */
    public volatile boolean geyserDetected = false;
    /** Troubleshooting knob: with Geyser, also accept connections from loopback that OmniLogin did not see. */
    public volatile boolean geyserAllowUnregistered = false;

    public volatile int timeoutMs = 4000;
    public volatile long cacheRegisteredSeconds = 1800;
    public volatile long cacheFreeSeconds = 300;
    public volatile String microsoftSession = "https://sessionserver.mojang.com/session/minecraft";
    public volatile String elybySession = "https://authserver.ely.by/session";
    public volatile String microsoftProfileApi = "https://api.mojang.com/users/profiles/minecraft/";
    public volatile String elybyProfileApi = "https://authserver.ely.by/api/users/profiles/minecraft/";

    /** "standalone" (OmniLogin checks every connection itself) or "backend" (a proxy already did). */
    public volatile String mode = "standalone";

    public volatile boolean authEnabled = true;
    public volatile String storage = "sqlite";
    public volatile String sqliteFile = "omnilogin.db";
    public volatile String mysqlHost = "localhost";
    public volatile int mysqlPort = 3306;
    public volatile String mysqlDatabase = "omnilogin";
    public volatile String mysqlUser = "root";
    public volatile String mysqlPassword = "";
    public volatile boolean mysqlSsl = false;
    /** "dialog" (Minecraft dialog window, chat as a fallback) or "chat". */
    public volatile String authUi = "dialog";
    public volatile int minPasswordLength = 6;
    public volatile int maxLoginAttempts = 5;
    public volatile int loginTimeoutSeconds = 60;
    public volatile int sessionMinutes = 30;
    public volatile java.util.List<String> allowedCommands = java.util.List.of("login", "l", "register", "reg");
    public volatile java.util.Map<String, String> authMessages = java.util.Map.of();

    public volatile boolean relayEnabled = true;
    public volatile String relayHost = "127.0.0.1";
    public volatile int relayPort = 8765;
    public volatile String msgElyOnlyProxy = "&cThis name belongs to an Ely.by account, which this proxy cannot verify.\n"
            + "&7Please use another name or a Microsoft account.";

    public volatile String msgReserved = "&cThis name is registered as an official Microsoft/Ely.by account.\n"
            + "&7Please use another name!";
    public volatile String msgCrackedDisabled = "&cCracked (unverified) players are not allowed on this server.\n"
            + "&7Please log in with a Microsoft or Ely.by account.";
    public volatile String msgLookupFailed = "&cCould not verify your name right now.\n&7Please try again in a moment.";
    public volatile String msgInvalidName = "&cInvalid username.";
    public volatile String msgOldClient = "&cPlease use Minecraft 1.19.3 or newer to log in with a premium account.";
    public volatile String msgUnverified = "&cYour connection could not be verified. Please reconnect.";
}
