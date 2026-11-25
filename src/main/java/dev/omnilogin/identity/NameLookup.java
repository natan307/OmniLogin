package dev.omnilogin.identity;

import dev.omnilogin.Settings;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Checks whether a name is registered on the Microsoft or Ely.by network (cached). */
public final class NameLookup {

    /** Outcome of a lookup. {@code error} is only set when nobody could say the name is registered. */
    public record Result(boolean microsoft, boolean elyby, boolean error) {
        public boolean registered() {
            return microsoft || elyby;
        }
    }

    private record Entry(Result result, long expires) {
    }

    private final Settings settings;
    private final Http http;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public NameLookup(Settings settings, Http http) {
        this.settings = settings;
        this.http = http;
    }

    /** Blocking. */
    public Result check(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Entry hit = cache.get(key);
        if (hit != null && hit.expires > now) {
            return hit.result;
        }

        int ms = http.get(settings.microsoftProfileApi + encode(name)).status();
        boolean microsoft = ms == 200;
        boolean error = !microsoft && !isAbsent(ms);
        boolean ely = false;
        if (!microsoft) {
            int es = http.get(settings.elybyProfileApi + encode(name)).status();
            ely = es == 200;
            error = error || (!ely && !isAbsent(es));
        }

        Result result = new Result(microsoft, ely, error && !(microsoft || ely));
        if (!result.error()) {
            long ttl = result.registered() ? settings.cacheRegisteredSeconds : settings.cacheFreeSeconds;
            if (cache.size() > 5000) {
                cache.values().removeIf(e -> e.expires <= now);
            }

            cache.put(key, new Entry(result, now + ttl * 1000L));
        }

        return result;
    }

    public void clearCache() {
        cache.clear();
    }

    /** 204/404, and 400 for names that cannot exist (for example Floodgate's ".Name"), mean "not registered". */
    private static boolean isAbsent(int status) {
        return status == 204 || status == 404 || status == 400;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
