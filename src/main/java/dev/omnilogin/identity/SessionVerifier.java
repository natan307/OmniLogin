package dev.omnilogin.identity;

import dev.omnilogin.Settings;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Confirms a client's session with Microsoft first, then Ely.by. */
public final class SessionVerifier {

    private final Settings settings;
    private final Http http;

    public SessionVerifier(Settings settings, Http http) {
        this.settings = settings;
        this.http = http;
    }

    /** Blocking. Returns null when neither network confirms the session. */
    public Verification hasJoined(String name, String serverHash) {
        String query = "/hasJoined?username=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&serverId=" + serverHash;
        Verification microsoft = parse(http.get(settings.microsoftSession + query), Status.MICROSOFT);
        if (microsoft != null) {
            return microsoft;
        }

        return parse(http.get(settings.elybySession + query), Status.ELYBY);
    }

    private static Verification parse(Http.Reply reply, Status status) {
        if (reply.status() != 200) {
            return null;
        }

        UUID id = Json.id(reply.body());
        String name = Json.name(reply.body());
        if (id == null || name == null) {
            return null;
        }

        return new Verification(id, name, Json.properties(reply.body()), status);
    }
}
