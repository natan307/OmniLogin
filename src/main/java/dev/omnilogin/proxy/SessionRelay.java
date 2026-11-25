package dev.omnilogin.proxy;

import dev.omnilogin.Settings;
import dev.omnilogin.identity.Http;
import dev.omnilogin.identity.IdentityRegistry;
import dev.omnilogin.identity.Json;
import dev.omnilogin.identity.Status;
import dev.omnilogin.net.LoginInterceptor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A local stand-in for Mojang's session server, used by Velocity so it can verify Ely.by players too.
 * Velocity is started with -Dmojang.sessionserver=http://127.0.0.1:PORT/session/minecraft/hasJoined .
 * Each login is tried on Microsoft, then Ely.by. If neither knows the player, the relay answers with a harmless
 * offline profile and records that the connection has to be kicked, so the plugin can show a proper message.
 */
public final class SessionRelay {

    private static final Pattern NAME_OK = Pattern.compile("^[A-Za-z0-9_.\\-]{1,16}$");

    private final Settings settings;
    private final Http http;
    private final IdentityRegistry registry;
    private final Consumer<String> info;
    private HttpServer server;
    private ExecutorService pool;

    public SessionRelay(Settings settings, Http http, IdentityRegistry registry, Consumer<String> info) {
        this.settings = settings;
        this.http = http;
        this.registry = registry;
        this.info = info;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(settings.relayHost),
                settings.relayPort), 0);
        server.createContext("/session/minecraft/hasJoined", this::hasJoined);
        pool = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "OmniLogin-Relay");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(pool);
        server.start();
        info.accept("Session relay listening on http://" + settings.relayHost + ":" + server.getAddress().getPort());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }

        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void hasJoined(HttpExchange exchange) throws IOException {
        try {
            String query = exchange.getRequestURI().getRawQuery();
            String name = param(query, "username");
            if (name == null || !NAME_OK.matcher(name).matches()) {
                send(exchange, 204, null);
                return;
            }

            Http.Reply microsoft = http.get(settings.microsoftSession + "/hasJoined?" + query);
            UUID id = microsoft.status() == 200 ? Json.id(microsoft.body()) : null;
            if (id != null) {
                registry.put(id, name, Status.MICROSOFT);
                send(exchange, 200, microsoft.body());
                return;
            }

            Http.Reply elyby = http.get(settings.elybySession + "/hasJoined?" + query);
            id = elyby.status() == 200 ? Json.id(elyby.body()) : null;
            if (id != null) {
                registry.put(id, name, Status.ELYBY);
                send(exchange, 200, elyby.body());
                return;
            }

            // Not verified. Velocity only asks for names that are registered, so this is someone using a name that
            // is not theirs. Answer with a profile so the login reaches the plugin, which then kicks with a message.
            UUID offline = LoginInterceptor.offlineUuid(name);
            registry.put(offline, new IdentityRegistry.Entry(name, Status.CRACKED, settings.msgReserved));
            send(exchange, 200, "{\"id\":\"" + offline.toString().replace("-", "") + "\",\"name\":\"" + name
                    + "\",\"properties\":[]}");
        } catch (RuntimeException failure) {
            send(exchange, 204, null);
        }
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }

        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }

        return null;
    }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        try (exchange) {
            if (body == null || code == 204) {
                exchange.sendResponseHeaders(code, -1);
                return;
            }

            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        }
    }
}
