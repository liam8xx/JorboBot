package bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Small wrapper around the Twitch Helix API. Refreshes the broadcaster token automatically. */
public class Twitch {
    public record Result(boolean ok, String reason) {}

    private static final Path TOKEN_FILE = Path.of("twitch_tokens.json");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String clientId = Config.require("TWITCH_CLIENT_ID");
    private final String clientSecret = Config.require("TWITCH_CLIENT_SECRET");
    private final String channel = Config.require("TWITCH_CHANNEL");

    private volatile String accessToken = Config.get("TWITCH_ACCESS_TOKEN");
    private volatile String refreshToken = Config.get("TWITCH_REFRESH_TOKEN");
    private String broadcasterId;

    public Twitch() {
        // If the bot already refreshed the tokens before, use the newer saved ones.
        try {
            if (Files.exists(TOKEN_FILE)) {
                JsonObject o = JsonParser.parseString(Files.readString(TOKEN_FILE)).getAsJsonObject();
                accessToken = o.get("access_token").getAsString();
                refreshToken = o.get("refresh_token").getAsString();
            }
        } catch (Exception e) {
            System.out.println("[Twitch] Could not read twitch_tokens.json, using config.properties values.");
        }
    }

    private static String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private synchronized void refreshTokens() throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Failed to refresh Twitch token: " + res.body());
        }

        JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
        accessToken = o.get("access_token").getAsString();
        refreshToken = o.get("refresh_token").getAsString();

        JsonObject saved = new JsonObject();
        saved.addProperty("access_token", accessToken);
        saved.addProperty("refresh_token", refreshToken);
        Files.writeString(TOKEN_FILE, saved.toString());
        System.out.println("[Twitch] Token refreshed and saved.");
    }

    private HttpResponse<String> api(String method, String endpoint, Map<String, String> params, boolean retry)
            throws IOException, InterruptedException {
        URI uri = URI.create("https://api.twitch.tv/helix/" + endpoint + "?" + encode(params));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId);
        HttpRequest req = method.equals("POST")
                ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                : builder.GET().build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401 && retry) {
            refreshTokens();
            return api(method, endpoint, params, false);
        }
        return res;
    }

    /** Returns the Twitch user ID for a login name, or null if it doesn't exist. */
    public String getUserId(String login) throws IOException, InterruptedException {
        String clean = login.toLowerCase().replace("@", "").trim();
        if (clean.isEmpty()) return null;
        HttpResponse<String> res = api("GET", "users", Map.of("login", clean), true);
        if (res.statusCode() == 400) return null;
        if (res.statusCode() != 200) throw new IOException("Twitch error " + res.statusCode() + ": " + res.body());
        JsonArray data = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("data");
        return data.size() == 0 ? null : data.get(0).getAsJsonObject().get("id").getAsString();
    }

    private synchronized String getBroadcasterId() throws IOException, InterruptedException {
        if (broadcasterId == null) {
            broadcasterId = getUserId(channel);
            if (broadcasterId == null) throw new IOException("Could not find Twitch channel: " + channel);
        }
        return broadcasterId;
    }

    /** True if the user is a VIP on your channel. */
    public boolean isVip(String userId) throws IOException, InterruptedException {
        HttpResponse<String> res = api("GET", "channels/vips",
                Map.of("broadcaster_id", getBroadcasterId(), "user_id", userId), true);
        if (res.statusCode() != 200) throw new IOException("Twitch error " + res.statusCode() + ": " + res.body());
        JsonArray data = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("data");
        return data.size() > 0;
    }

    /** Gives the user VIP on your channel. */
    public Result addVip(String userId) throws IOException, InterruptedException {
        HttpResponse<String> res = api("POST", "channels/vips",
                Map.of("broadcaster_id", getBroadcasterId(), "user_id", userId), true);
        if (res.statusCode() == 204) return new Result(true, "");
        if (res.statusCode() == 422) return new Result(false, "They are already a VIP or a moderator on Twitch.");
        return new Result(false, "Twitch error " + res.statusCode() + ": " + res.body());
    }
}
