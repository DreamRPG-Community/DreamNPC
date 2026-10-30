package cn.mythicland.dreamnpc;

import cn.mythicland.dreamnpc.model.SkinData;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches signed player textures from Mojang's public session APIs.
 */
public final class MojangSkinFetcher {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\""
    );
    private static final Pattern VALUE_PATTERN = Pattern.compile(
            "\"name\"\\s*:\\s*\"textures\".*?\"value\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL
    );
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile(
            "\"name\"\\s*:\\s*\"textures\".*?\"signature\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL
    );

    private static String request(String endpoint) {
        return request(endpoint, 10_000);
    }

    static String request(String endpoint, int timeoutMillis) {
        int timeouts = 0;
        while (true) {
            HttpURLConnection connection = null;
            try {
                URL url = URI.create(endpoint).toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);
                connection.setRequestProperty("User-Agent", "DreamRPG-DreamNPC/1.0");
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Mojang request failed with HTTP " + status);
                }
                try (InputStream stream = connection.getInputStream()) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (SocketTimeoutException exception) {
                // A read-only profile request is safe to retry once after a transient timeout.
                if (timeouts++ > 0) throw new IllegalStateException("Mojang request timed out twice", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("Mojang request failed", exception);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    private static String encodePath(String value) {
        return value.replace("%", "%25").replace(" ", "%20");
    }

    /**
     * Fetches one player skin off the server thread.
     *
     * @param playerName player name
     * @return future texture data
     */
    public CompletableFuture<SkinData> fetch(String playerName) {
        String normalized = Objects.requireNonNull(playerName, "playerName").trim();
        return CompletableFuture.supplyAsync(() -> fetchBlocking(normalized));
    }

    private SkinData fetchBlocking(String playerName) {
        String uuidJson = request("https://api.mojang.com/users/profiles/minecraft/" + encodePath(playerName));
        Matcher uuidMatcher = UUID_PATTERN.matcher(uuidJson);
        if (!uuidMatcher.find()) throw new IllegalStateException("Mojang returned no player for " + playerName);
        String uuid = uuidMatcher.group(1);
        String profileJson =
                request("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
        Matcher valueMatcher = VALUE_PATTERN.matcher(profileJson);
        Matcher signatureMatcher = SIGNATURE_PATTERN.matcher(profileJson);
        if (!valueMatcher.find() || !signatureMatcher.find()) {
            throw new IllegalStateException("Mojang returned no signed textures for " + playerName);
        }
        return new SkinData(playerName, valueMatcher.group(1), signatureMatcher.group(1));
    }
}
