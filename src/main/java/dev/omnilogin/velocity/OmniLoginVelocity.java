package dev.omnilogin.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import dev.omnilogin.Settings;
import dev.omnilogin.identity.Http;
import dev.omnilogin.identity.IdentityRegistry;
import dev.omnilogin.identity.NameLookup;
import dev.omnilogin.proxy.ProxyConfig;
import dev.omnilogin.proxy.SessionRelay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;

/**
 * Velocity entry point. Registered names are forced into online mode (real verification), free names into offline
 * mode (cracked). With the session relay (see README) Ely.by players can be verified as well.
 * MIT License, Copyright (c) 2026 Natan307.
 */
public final class OmniLoginVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final IdentityRegistry registry = new IdentityRegistry();
    private Settings settings;
    private NameLookup lookup;
    private SessionRelay relay;
    private boolean relayActive;

    @Inject
    public OmniLoginVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        logger.info("Created by Natan307 - GitHub: @natan307 - Modrinth: natan307");
        settings = ProxyConfig.load(dataDirectory, getClass().getClassLoader(), logger::warn);
        Http http = new Http(settings);
        lookup = new NameLookup(settings, http);

        String flag = System.getProperty("mojang.sessionserver");
        relayActive = settings.relayEnabled && flag != null && flag.contains(":" + settings.relayPort);
        if (settings.relayEnabled) {
            relay = new SessionRelay(settings, http, registry, logger::info);
            try {
                relay.start();
            } catch (Exception failure) {
                logger.warn("Could not start the session relay: " + failure);
                relayActive = false;
            }
        }

        if (!proxy.getConfiguration().isOnlineMode()) {
            logger.warn("online-mode is false in velocity.toml. OmniLogin needs online-mode = true on Velocity.");
        }

        if (!relayActive) {
            logger.warn("Velocity was not started with -Dmojang.sessionserver=http://" + settings.relayHost + ":"
                    + settings.relayPort + "/session/minecraft/hasJoined : Ely.by accounts cannot be verified "
                    + "(Microsoft accounts and cracked players work).");
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (relay != null) {
            relay.stop();
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> {
            String name = event.getUsername();
            if (settings.bypassNames.contains(name.toLowerCase(Locale.ROOT))) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                return;
            }

            NameLookup.Result result = lookup.check(name);
            if (result.microsoft() || (result.elyby() && relayActive)) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            } else if (result.elyby()) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text(settings.msgElyOnlyProxy)));
            } else if (result.error() && settings.denyWhenLookupFails) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text(settings.msgLookupFailed)));
            } else if (!settings.allowCracked) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text(settings.msgCrackedDisabled)));
            } else {
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            }
        });
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onLogin(LoginEvent event) {
        IdentityRegistry.Entry entry = registry.consume(event.getPlayer().getUniqueId());
        if (entry != null && entry.denyMessage() != null) {
            event.setResult(ResultedEvent.ComponentResult.denied(text(entry.denyMessage())));
        }
    }

    private static Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }
}
