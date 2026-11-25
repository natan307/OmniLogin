package dev.omnilogin.bungee;

import dev.omnilogin.Settings;
import dev.omnilogin.identity.Http;
import dev.omnilogin.identity.NameLookup;
import dev.omnilogin.proxy.ProxyConfig;

import java.util.Locale;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/**
 * BungeeCord entry point. BungeeCord cannot change its session server, so Ely.by accounts cannot be verified here
 * (use Velocity for that). Per connection: a name registered on Microsoft is forced into online mode (real
 * verification), a name registered only on Ely.by is refused, and a free name joins as cracked.
 * MIT License, Copyright (c) 2026 Natan307.
 */
public final class OmniLoginBungee extends Plugin implements Listener {

    private Settings settings;
    private NameLookup lookup;

    @Override
    public void onEnable() {
        getLogger().info("Created by Natan307 - GitHub: @natan307 - Modrinth: natan307");
        settings = ProxyConfig.load(getDataFolder().toPath(), getClass().getClassLoader(), getLogger()::warning);
        lookup = new NameLookup(settings, new Http(settings));
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().warning("BungeeCord cannot verify Ely.by accounts (use Velocity for that). "
                + "Microsoft login and cracked name protection are active.");
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent event) {
        if (event.isCancelled()) {
            return;
        }

        PendingConnection connection = event.getConnection();
        String name = connection.getName();
        event.registerIntent(this);
        getProxy().getScheduler().runAsync(this, () -> {
            try {
                decide(event, connection, name);
            } catch (RuntimeException failure) {
                getLogger().warning("Error while checking " + name + ": " + failure);
                deny(event, settings.msgLookupFailed);
            } finally {
                event.completeIntent(this);
            }
        });
    }

    private void decide(PreLoginEvent event, PendingConnection connection, String name) {
        if (settings.bypassNames.contains(name.toLowerCase(Locale.ROOT))) {
            connection.setOnlineMode(false);
            return;
        }

        NameLookup.Result result = lookup.check(name);
        if (result.microsoft()) {
            connection.setOnlineMode(true);
        } else if (result.elyby()) {
            deny(event, settings.msgElyOnlyProxy);
        } else if (result.error() && settings.denyWhenLookupFails) {
            deny(event, settings.msgLookupFailed);
        } else if (!settings.allowCracked) {
            deny(event, settings.msgCrackedDisabled);
        } else {
            connection.setOnlineMode(false);
        }
    }

    private void deny(PreLoginEvent event, String message) {
        event.setCancelled(true);
        event.setReason(new TextComponent(TextComponent.fromLegacyText(
                ChatColor.translateAlternateColorCodes('&', message))));
    }
}
