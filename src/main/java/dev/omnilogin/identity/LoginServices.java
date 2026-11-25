package dev.omnilogin.identity;

import dev.omnilogin.Settings;
import dev.omnilogin.net.LoginInterceptor;
import dev.omnilogin.net.LoginInterceptor.Decision;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The real services behind the login interceptor. */
public final class LoginServices implements LoginInterceptor.Services {

    private final Settings settings;
    private final NameLookup lookup;
    private final SessionVerifier verifier;
    private final IdentityRegistry registry;
    private final KeyPair keyPair;
    private final Logger log;

    public LoginServices(Settings settings, NameLookup lookup, SessionVerifier verifier, IdentityRegistry registry,
            Logger log) throws GeneralSecurityException {
        this.settings = settings;
        this.lookup = lookup;
        this.verifier = verifier;
        this.registry = registry;
        this.log = log;

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        this.keyPair = generator.generateKeyPair();
    }

    @Override
    public Decision decide(String name) {
        if (settings.bypassNames.contains(name.toLowerCase(Locale.ROOT))) {
            return Decision.FREE;
        }

        NameLookup.Result result = lookup.check(name);
        if (result.registered()) {
            return Decision.REGISTERED;
        }

        if (result.error() && settings.denyWhenLookupFails) {
            return Decision.DENY_LOOKUP_FAILED;
        }

        return settings.allowCracked ? Decision.FREE : Decision.DENY_CRACKED_DISABLED;
    }

    @Override
    public Verification verify(String name, String serverHash) {
        return verifier.hasJoined(name, serverHash);
    }

    @Override
    public void register(UUID id, String name, Status status) {
        registry.put(id, name, status);
    }

    @Override
    public boolean usePremiumUuid() {
        return settings.usePremiumUuid;
    }

    @Override
    public boolean forwardIdentity() {
        return settings.forwardIdentity;
    }

    @Override
    public KeyPair keyPair() {
        return keyPair;
    }

    @Override
    public String denyMessage(Decision decision) {
        return switch (decision) {
            case DENY_CRACKED_DISABLED -> settings.msgCrackedDisabled;
            case DENY_LOOKUP_FAILED -> settings.msgLookupFailed;
            default -> settings.msgReserved;
        };
    }

    @Override
    public String reservedMessage() {
        return settings.msgReserved;
    }

    @Override
    public String invalidNameMessage() {
        return settings.msgInvalidName;
    }

    @Override
    public long stepTimeoutMillis() {
        return settings.stepTimeoutMs;
    }

    @Override
    public boolean debugEnabled() {
        return settings.debug;
    }

    @Override
    public void debug(String message) {
        if (settings.debug) {
            log.info("[debug] " + message);
        }
    }

    @Override
    public void error(String message, Throwable failure) {
        log.log(Level.WARNING, message, failure);
    }

    @Override
    public String oldClientMessage() {
        return settings.msgOldClient;
    }
}
