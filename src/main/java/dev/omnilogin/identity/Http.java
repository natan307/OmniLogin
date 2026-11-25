package dev.omnilogin.identity;

import dev.omnilogin.Settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Small blocking HTTP GET used by the lookups. Never call it from an event loop or region thread. */
public final class Http {

    /** Status code (-1 on failure) and body. */
    public record Reply(int status, String body) {
    }

    private final Settings settings;
    private final HttpClient client;

    public Http(Settings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMs)).build();
    }

    public Reply get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(settings.timeoutMs))
                    .header("User-Agent", "OmniLogin/1.0")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Reply(-1, "");
        } catch (Exception failure) {
            return new Reply(-1, "");
        }
    }
}
