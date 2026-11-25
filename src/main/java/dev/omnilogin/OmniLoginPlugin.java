package dev.omnilogin;

import dev.omnilogin.auth.AuthCommands;
import dev.omnilogin.auth.AuthDatabase;
import dev.omnilogin.auth.AuthListener;
import dev.omnilogin.auth.AuthManager;
import dev.omnilogin.identity.Http;
import dev.omnilogin.identity.IdentityRegistry;
import dev.omnilogin.identity.LoginServices;
import dev.omnilogin.identity.NameLookup;
import dev.omnilogin.identity.SessionVerifier;
import dev.omnilogin.net.LoginChannelHandler;
import dev.omnilogin.net.NettyInjector;
import dev.omnilogin.util.Sched;

import java.io.File;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * OmniLogin for Paper, Folia, Purpur, Spigot, Bukkit and their forks.
 * MIT License, Copyright (c) 2026 Natan307.
 */
public final class OmniLoginPlugin extends JavaPlugin implements CommandExecutor {

    private static final int BSTATS_ID = 35481;

    private final Settings settings = new Settings();
    private final IdentityRegistry registry = new IdentityRegistry();
    private ExecutorService blocking;
    private NameLookup lookup;
    private NettyInjector injector;
    private AuthManager auth;
    private Sched sched;
    private boolean intercepting;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings(getConfig());
        sched = Sched.create(this);
        if (settings.debug) {
            getLogger().info("Debug logging is on: every login is logged in detail.");
        }

        getLogger().info("Created by Natan307 - GitHub: @natan307 - Modrinth: natan307");

        if (getConfig().getBoolean("bstats", true)) {
            Metrics metrics = new Metrics(this, BSTATS_ID);
            metrics.addCustomChart(new SimplePie("mode", () -> settings.mode));
            metrics.addCustomChart(new SimplePie("password_login",
                    () -> settings.authEnabled ? settings.storage.toLowerCase(Locale.ROOT) : "off"));
        }

        boolean backend = "backend".equals(settings.mode);
        if (!backend && !startInterception()) {
            return;
        }

        if (settings.authEnabled) {
            startAuth();
        }

        getServer().getPluginManager().registerEvents(new IdentityListener(settings, registry, auth), this);
        getCommand("omnilogin").setExecutor(this);
    }

    @Override
    public void onDisable() {
        if (injector != null) {
            injector.uninject();
        }

        if (blocking != null) {
            blocking.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------------------------- startup

    /** Returns false when the plugin cannot protect the server and has been switched off. */
    private boolean startInterception() {
        if (getServer().getOnlineMode()) {
            getLogger().warning("online-mode is true in server.properties. OmniLogin needs online-mode=false "
                    + "(it does the Microsoft/Ely.by verification itself). OmniLogin is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        File spigot = new File(getServer().getWorldContainer(), "spigot.yml");
        boolean bungeeFlag = YamlConfiguration.loadConfiguration(spigot).getBoolean("settings.bungeecord", false);
        settings.geyserDetected = detectGeyser();

        if (settings.geyserDetected) {
            if (bungeeFlag) {
                return unsafe("Geyser/Floodgate is installed, so spigot.yml needs 'settings.bungeecord: false' "
                        + "(Floodgate's Bedrock data would be lost, and plain mode with it on is not safe). "
                        + "Set it to false and restart.");
            }

            settings.forwardIdentity = false;
            getLogger().info("Geyser/Floodgate detected: Bedrock players are supported (plain mode).");
        } else {
            settings.forwardIdentity = bungeeFlag;
            if (bungeeFlag) {
                getLogger().info("spigot.yml has 'settings.bungeecord: true': verified players keep their skin data"
                        + (settings.usePremiumUuid ? " and real UUID." : "."));
            } else {
                getLogger().info("Plain mode (spigot.yml 'settings.bungeecord' is false): verified players use "
                        + "their offline UUID and no skin data is passed on (use SkinsRestorer for skins, or set "
                        + "'settings.bungeecord: true' to pass the real skin and UUID).");
            }
        }

        if (settings.usePremiumUuid && !settings.forwardIdentity) {
            getLogger().warning("'login.use-premium-uuid: true' needs 'settings.bungeecord: true' in spigot.yml "
                    + "(and no Geyser). Using offline UUIDs.");
        }

        try {
            Http http = new Http(settings);
            lookup = new NameLookup(settings, http);
            LoginServices services = new LoginServices(settings, lookup, new SessionVerifier(settings, http), registry,
                    getLogger());
            blocking = Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable, "OmniLogin-Worker");
                thread.setDaemon(true);
                return thread;
            });

            injector = new NettyInjector(getLogger(), () -> new LoginChannelHandler(services, blocking));
            intercepting = injector.inject();
        } catch (Exception | LinkageError failure) {
            getLogger().log(Level.SEVERE, "Could not start the login protection", failure);
        }

        if (!intercepting) {
            return unsafe("Could not hook into the server's network layer on this server version.");
        }

        return true;
    }

    /** With online-mode=false and no protection anyone could join as anyone, so stop the server. */
    private boolean unsafe(String reason) {
        getLogger().severe(reason);
        getServer().getPluginManager().disablePlugin(this);
        if (getConfig().getBoolean("shutdown-if-unsafe", true)) {
            getLogger().severe("online-mode is false and OmniLogin is not protecting the server: shutting down. "
                    + "Fix the problem above, or set 'shutdown-if-unsafe: false' in OmniLogin's config.yml.");
            getServer().shutdown();
        }

        return false;
    }

    /** geyser.support: auto (detect), true or false. */
    private boolean detectGeyser() {
        String mode = getConfig().getString("geyser.support", "auto").toLowerCase(Locale.ROOT);
        if ("true".equals(mode)) {
            return true;
        }

        if ("false".equals(mode)) {
            return false;
        }

        return getServer().getPluginManager().getPlugin("Geyser-Spigot") != null
                || getServer().getPluginManager().getPlugin("floodgate") != null
                || getServer().getPluginManager().getPlugin("Geyser") != null;
    }

    private void startAuth() {
        try {
            AuthDatabase database = new AuthDatabase(settings, getDataFolder());
            database.createTable();
            auth = new AuthManager(settings, database, sched, getLogger());
            getServer().getPluginManager().registerEvents(new AuthListener(auth), this);

            AuthCommands commands = new AuthCommands(auth);
            for (String name : Arrays.asList("register", "login", "changepassword", "omniauth")) {
                getCommand(name).setExecutor(commands);
            }

            attachDialogUi();
            getLogger().info("Password login enabled (" + settings.storage + ").");
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "Could not open the password login database, password login is off", ex);
        }
    }

    /** Uses Minecraft's dialog window for the password forms when the server has the Dialog API (Paper 1.21.7+). */
    private void attachDialogUi() {
        if (!"dialog".equals(settings.authUi)) {
            return;
        }

        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            Object form = Class.forName("dev.omnilogin.auth.DialogUi")
                    .getConstructor(AuthManager.class).newInstance(auth);
            auth.setUi((dev.omnilogin.auth.AuthUi) form);
            getLogger().info("Password forms use Minecraft dialogs (chat commands still work).");
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            getLogger().info("Dialog API not available (needs Paper 1.21.7+): using chat commands for the password login.");
        }
    }

    private void loadSettings(FileConfiguration c) {
        settings.mode = c.getString("mode", "standalone").toLowerCase(Locale.ROOT);
        settings.allowCracked = c.getBoolean("login.allow-cracked", true);
        settings.denyWhenLookupFails = c.getBoolean("login.deny-when-lookup-fails", true);
        Set<String> bypass = new HashSet<>();
        for (String name : c.getStringList("login.bypass-names")) {
            bypass.add(name.toLowerCase(Locale.ROOT));
        }

        settings.bypassNames = Set.copyOf(bypass);
        settings.usePremiumUuid = c.getBoolean("login.use-premium-uuid", false);
        settings.enforceIdentity = c.getBoolean("login.enforce-identity", true);
        settings.debug = c.getBoolean("debug", false);
        settings.stepTimeoutMs = c.getLong("login.step-timeout-ms", 15000L);
        settings.geyserAllowUnregistered = c.getBoolean("geyser.allow-unregistered-loopback", false);
        settings.timeoutMs = c.getInt("upstream.timeout-ms", 4000);
        settings.cacheRegisteredSeconds = c.getLong("upstream.cache.registered-seconds", 1800L);
        settings.cacheFreeSeconds = c.getLong("upstream.cache.free-seconds", 300L);
        settings.microsoftSession = trim(c.getString("upstream.microsoft-session", settings.microsoftSession));
        settings.elybySession = trim(c.getString("upstream.elyby-session", settings.elybySession));
        settings.microsoftProfileApi = c.getString("upstream.microsoft-profile-api", settings.microsoftProfileApi);
        settings.elybyProfileApi = c.getString("upstream.elyby-profile-api", settings.elybyProfileApi);

        settings.authEnabled = c.getBoolean("auth.enabled", true);
        settings.authUi = c.getString("auth.ui", "dialog").toLowerCase(Locale.ROOT);
        settings.storage = c.getString("auth.storage", "sqlite");
        settings.sqliteFile = c.getString("auth.sqlite-file", "omnilogin.db");
        settings.mysqlHost = c.getString("auth.mysql.host", "localhost");
        settings.mysqlPort = c.getInt("auth.mysql.port", 3306);
        settings.mysqlDatabase = c.getString("auth.mysql.database", "omnilogin");
        settings.mysqlUser = c.getString("auth.mysql.username", "root");
        settings.mysqlPassword = c.getString("auth.mysql.password", "");
        settings.mysqlSsl = c.getBoolean("auth.mysql.use-ssl", false);
        settings.minPasswordLength = c.getInt("auth.min-password-length", 6);
        settings.maxLoginAttempts = c.getInt("auth.max-login-attempts", 5);
        settings.loginTimeoutSeconds = c.getInt("auth.login-timeout-seconds", 60);
        settings.sessionMinutes = c.getInt("auth.session-minutes", 30);

        java.util.List<String> allowed = c.getStringList("auth.allowed-commands");
        settings.allowedCommands = allowed.isEmpty() ? settings.allowedCommands : java.util.List.copyOf(allowed);

        Map<String, String> authMessages = new HashMap<>();
        for (String key : AuthManager.DEFAULT_MESSAGES.keySet()) {
            String text = c.getString("auth.messages." + key);
            if (text != null) {
                authMessages.put(key, text);
            }
        }

        settings.authMessages = Map.copyOf(authMessages);

        settings.msgReserved = c.getString("messages.reserved-name", settings.msgReserved);
        settings.msgCrackedDisabled = c.getString("messages.cracked-disabled", settings.msgCrackedDisabled);
        settings.msgLookupFailed = c.getString("messages.lookup-failed", settings.msgLookupFailed);
        settings.msgInvalidName = c.getString("messages.invalid-name", settings.msgInvalidName);
        settings.msgOldClient = c.getString("messages.old-client", settings.msgOldClient);
        settings.msgUnverified = c.getString("messages.unverified", settings.msgUnverified);
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ------------------------------------------------------------------------------------------ /omnilogin

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("omnilogin.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if ("check".equals(sub) && args.length >= 2) {
            checkName(sender, args[1]);
        } else if ("about".equals(sub)) {
            sender.sendMessage(ChatColor.DARK_GRAY + "OmniLogin " + getDescription().getVersion()
                    + " - created by " + ChatColor.GRAY + "Natan307");
            sender.sendMessage(ChatColor.DARK_GRAY + "GitHub: " + ChatColor.GRAY + "@natan307"
                    + ChatColor.DARK_GRAY + " | Modrinth: " + ChatColor.GRAY + "natan307");
        } else {
            sender.sendMessage(ChatColor.GOLD + "OmniLogin " + ChatColor.GRAY + "v" + getDescription().getVersion());
            sender.sendMessage(ChatColor.YELLOW + "Mode: " + ChatColor.WHITE + settings.mode
                    + ChatColor.YELLOW + " | protecting logins: " + ChatColor.WHITE
                    + ("backend".equals(settings.mode) ? "by the proxy" : String.valueOf(intercepting)));
            sender.sendMessage(ChatColor.YELLOW + "Cracked players: " + ChatColor.WHITE
                    + (settings.allowCracked ? "allowed (free names only)" : "disabled"));
            sender.sendMessage(ChatColor.YELLOW + "Password login: " + ChatColor.WHITE
                    + (auth == null ? "off" : settings.storage));
        }

        return true;
    }

    private void checkName(CommandSender sender, String name) {
        if (lookup == null) {
            sender.sendMessage(ChatColor.RED + "Name lookups are not available in backend mode.");
            return;
        }

        sched.async(() -> {
            NameLookup.Result result = lookup.check(name);
            String text = result.error() ? "lookup failed"
                    : result.registered() ? "REGISTERED (" + (result.microsoft() ? "Microsoft" : "Ely.by") + ")"
                    : "free";
            sender.sendMessage(ChatColor.YELLOW + name + ": " + ChatColor.WHITE + text);
        });
    }
}
