package dev.omnilogin.auth;

import dev.omnilogin.Settings;
import dev.omnilogin.util.Sched;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Password login for cracked players. Players that OmniLogin verified through Microsoft or Ely.by skip it.
 */
public final class AuthManager {

    /** English defaults for every message; config.yml can override them. */
    public static final Map<String, String> DEFAULT_MESSAGES = new LinkedHashMap<>();

    private static final long PROMPT_DELAY_TICKS = 30L;
    private static final long REMINDER_TICKS = 100L;
    private static final int REMINDER_SECONDS = 5;
    private static final int MAX_PASSWORD_LENGTH = 128;

    static {
        DEFAULT_MESSAGES.put("register-prompt", "&eWelcome! Register with &6/register <password> <password>");
        DEFAULT_MESSAGES.put("login-prompt", "&eWelcome back! Log in with &6/login <password>");
        DEFAULT_MESSAGES.put("reminder", "&ePlease &6/login <password> &eor &6/register <password> <password>");
        DEFAULT_MESSAGES.put("session-resumed", "&aWelcome back! You were logged in automatically.");
        DEFAULT_MESSAGES.put("registered", "&aRegistered successfully. You are now logged in.");
        DEFAULT_MESSAGES.put("logged-in", "&aLogged in successfully.");
        DEFAULT_MESSAGES.put("already-registered", "&cThis name is already registered. Use &6/login <password>");
        DEFAULT_MESSAGES.put("not-registered", "&cThis name is not registered. Use &6/register <password> <password>");
        DEFAULT_MESSAGES.put("already-logged-in", "&cYou are already logged in.");
        DEFAULT_MESSAGES.put("must-login", "&cPlease log in first.");
        DEFAULT_MESSAGES.put("wrong-password", "&cWrong password. Attempts left: %attempts%");
        DEFAULT_MESSAGES.put("too-many-attempts", "&cToo many wrong passwords.");
        DEFAULT_MESSAGES.put("timeout", "&cLogin timed out. Please rejoin and log in.");
        DEFAULT_MESSAGES.put("password-mismatch", "&cThe passwords do not match.");
        DEFAULT_MESSAGES.put("password-too-short", "&cThe password must be at least %min% characters long.");
        DEFAULT_MESSAGES.put("password-too-long", "&cThe password is too long.");
        DEFAULT_MESSAGES.put("password-changed", "&aYour password was changed.");
        DEFAULT_MESSAGES.put("usage-register", "&cUsage: /register <password> <password>");
        DEFAULT_MESSAGES.put("usage-login", "&cUsage: /login <password>");
        DEFAULT_MESSAGES.put("usage-changepassword", "&cUsage: /changepassword <old password> <new password>");
        DEFAULT_MESSAGES.put("internal-error", "&cAn internal error occurred. Please try again later.");
        DEFAULT_MESSAGES.put("dialog-title-register", "&6Register");
        DEFAULT_MESSAGES.put("dialog-title-login", "&6Log in");
        DEFAULT_MESSAGES.put("dialog-hint-register", "Choose a password for your account.");
        DEFAULT_MESSAGES.put("dialog-hint-login", "Enter your password to continue.");
        DEFAULT_MESSAGES.put("dialog-label-password", "Password");
        DEFAULT_MESSAGES.put("dialog-label-confirm", "Repeat password");
        DEFAULT_MESSAGES.put("dialog-button-register", "Register");
        DEFAULT_MESSAGES.put("dialog-button-login", "Log in");
    }

    /** A database action that runs off the game threads. */
    private interface SqlTask {
        void run() throws SQLException;
    }

    private record Session(String ip, long expires) {
    }

    private final Settings settings;
    private final AuthDatabase database;
    private final Sched sched;
    private final Logger log;

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> premium = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Sched.Handle> reminders = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile AuthUi ui;

    public AuthManager(Settings settings, AuthDatabase database, Sched sched, Logger log) {
        this.settings = settings;
        this.database = database;
        this.sched = sched;
        this.log = log;
    }

    /** Sets the form used to ask for passwords (null = chat commands only). */
    public void setUi(AuthUi ui) {
        this.ui = ui;
    }

    public String message(String key) {
        String text = settings.authMessages.get(key);
        if (text == null) {
            text = DEFAULT_MESSAGES.getOrDefault(key, key);
        }

        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public boolean isLocked(Player player) {
        return !authenticated.contains(player.getUniqueId());
    }

    public boolean isCommandAllowed(String command) {
        return settings.allowedCommands.contains(command.toLowerCase(Locale.ROOT));
    }

    /** Called before the player joins: OmniLogin (or the proxy) already verified this account. */
    public void markPremium(UUID id) {
        premium.add(id);
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        if (premium.remove(id)) {
            authenticated.add(id);
            return;
        }

        if (authenticated.contains(id)) {
            return;
        }

        AtomicInteger left = new AtomicInteger(settings.loginTimeoutSeconds);
        Sched.Handle reminder = sched.every(player, () -> {
            if (!player.isOnline() || authenticated.contains(id)) {
                cancelReminder(id);
                return;
            }

            if (left.addAndGet(-REMINDER_SECONDS) <= 0) {
                cancelReminder(id);
                kick(player, "timeout");
                return;
            }

            player.sendMessage(message("reminder"));
        }, REMINDER_TICKS, REMINDER_TICKS);
        reminders.put(id, reminder);

        sched.later(player, () -> prompt(player), PROMPT_DELAY_TICKS);
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        premium.remove(id);
        if (authenticated.remove(id) && settings.sessionMinutes > 0) {
            long expires = System.currentTimeMillis() + settings.sessionMinutes * 60_000L;
            sessions.put(player.getName().toLowerCase(Locale.ROOT), new Session(ip(player), expires));
        }

        attempts.remove(id);
        cancelReminder(id);
    }

    private void prompt(Player player) {
        if (!player.isOnline() || authenticated.contains(player.getUniqueId())) {
            return;
        }

        async(player, () -> {
            AuthDatabase.Account account = database.find(player.getName());
            if (account != null && canResume(player.getName(), ip(player))) {
                authenticate(player);
                player.sendMessage(message("session-resumed"));
            } else {
                player.sendMessage(message(account == null ? "register-prompt" : "login-prompt"));
                AuthUi form = ui;
                if (form != null) {
                    boolean register = account == null;
                    sched.run(player, () -> form.show(player, register, null));
                }
            }
        });
    }

    /** Shows the form again after a failed attempt, so the player does not have to type a command. */
    private void reshow(Player player, String error) {
        AuthUi form = ui;
        if (form == null || !player.isOnline() || !isLocked(player)) {
            return;
        }

        async(player, () -> {
            AuthDatabase.Account account = database.find(player.getName());
            sched.run(player, () -> form.show(player, account == null, error));
        });
    }

    private void fail(Player player, String text, boolean viaDialog) {
        player.sendMessage(text);
        if (viaDialog) {
            reshow(player, text);
        }
    }

    private boolean canResume(String name, String ip) {
        Session session = sessions.get(name.toLowerCase(Locale.ROOT));
        return session != null && session.ip().equals(ip) && session.expires() > System.currentTimeMillis();
    }

    private void authenticate(Player player) {
        UUID id = player.getUniqueId();
        authenticated.add(id);
        attempts.remove(id);
        cancelReminder(id);
    }

    private void cancelReminder(UUID id) {
        Sched.Handle handle = reminders.remove(id);
        if (handle != null) {
            handle.cancel();
        }
    }

    // ------------------------------------------------------------------------------------------ commands

    public void register(Player player, String password, String confirm) {
        register(player, password, confirm, false);
    }

    public void register(Player player, String password, String confirm, boolean viaDialog) {
        if (!isLocked(player)) {
            player.sendMessage(message("already-logged-in"));
            return;
        }

        if (!password.equals(confirm)) {
            fail(player, message("password-mismatch"), viaDialog);
            return;
        }

        String problem = validate(password);
        if (problem != null) {
            fail(player, problem, viaDialog);
            return;
        }

        async(player, () -> {
            if (database.find(player.getName()) != null) {
                fail(player, message("already-registered"), viaDialog);
                return;
            }

            database.register(player.getName(), PasswordHasher.hash(password), ip(player));
            authenticate(player);
            player.sendMessage(message("registered"));
        });
    }

    public void login(Player player, String password) {
        login(player, password, false);
    }

    public void login(Player player, String password, boolean viaDialog) {
        if (!isLocked(player)) {
            player.sendMessage(message("already-logged-in"));
            return;
        }

        async(player, () -> {
            AuthDatabase.Account account = database.find(player.getName());
            if (account == null) {
                fail(player, message("not-registered"), viaDialog);
                return;
            }

            if (PasswordHasher.verify(password, account.hash())) {
                database.updateLogin(player.getName(), ip(player));
                authenticate(player);
                player.sendMessage(message("logged-in"));
                return;
            }

            int used = attempts.merge(player.getUniqueId(), 1, Integer::sum);
            if (used >= settings.maxLoginAttempts) {
                kick(player, "too-many-attempts");
            } else {
                String left = String.valueOf(settings.maxLoginAttempts - used);
                fail(player, message("wrong-password").replace("%attempts%", left), viaDialog);
            }
        });
    }

    public void changePassword(Player player, String oldPassword, String newPassword) {
        if (isLocked(player)) {
            player.sendMessage(message("must-login"));
            return;
        }

        String problem = validate(newPassword);
        if (problem != null) {
            player.sendMessage(problem);
            return;
        }

        async(player, () -> {
            AuthDatabase.Account account = database.find(player.getName());
            if (account == null || !PasswordHasher.verify(oldPassword, account.hash())) {
                player.sendMessage(message("wrong-password").replace("%attempts%", "-"));
                return;
            }

            database.updatePassword(player.getName(), PasswordHasher.hash(newPassword));
            player.sendMessage(message("password-changed"));
        });
    }

    public void adminUnregister(CommandSender sender, String name) {
        async(sender, () -> {
            int removed = database.delete(name);
            sender.sendMessage(removed > 0 ? "Removed the account of " + name + "." : name + " is not registered.");
        });
    }

    public void adminSetPassword(CommandSender sender, String name, String password) {
        String problem = validate(password);
        if (problem != null) {
            sender.sendMessage(problem);
            return;
        }

        async(sender, () -> {
            String hash = PasswordHasher.hash(password);
            if (database.updatePassword(name, hash) == 0) {
                database.register(name, hash, "admin");
            }

            sender.sendMessage("Password of " + name + " was set.");
        });
    }

    // -------------------------------------------------------------------------------------------- helpers

    private String validate(String password) {
        if (password.length() < settings.minPasswordLength) {
            return message("password-too-short").replace("%min%", String.valueOf(settings.minPasswordLength));
        }

        if (password.length() > MAX_PASSWORD_LENGTH) {
            return message("password-too-long");
        }

        return null;
    }

    private void kick(Player player, String key) {
        sched.run(player, () -> player.kickPlayer(message(key)));
    }

    private void async(CommandSender sender, SqlTask task) {
        sched.async(() -> {
            try {
                task.run();
            } catch (SQLException ex) {
                log.log(Level.WARNING, "Database error in the password login", ex);
                sender.sendMessage(message("internal-error"));
            }
        });
    }

    private static String ip(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null ? "unknown" : address.getAddress().getHostAddress();
    }
}
