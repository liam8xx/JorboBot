package bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Small website for the Verify button:
 *   /login    -> sends the person to Discord's login page
 *   /callback -> Discord sends them back here; we read their linked Twitch,
 *                check VIP on Twitch, and give the Discord role.
 */
public class WebServer {
    private static final String DISCORD_API = "https://discord.com/api/v10";

    private final HttpClient http = HttpClient.newHttpClient();
    private final SecureRandom random = new SecureRandom();

    private final JDA jda;
    private final Twitch twitch;
    private final LinkStore links;

    private final String clientId = Config.require("DISCORD_CLIENT_ID");
    private final String clientSecret = Config.require("DISCORD_CLIENT_SECRET");
    private final String guildId = Config.require("GUILD_ID");
    private final String roleId = Config.require("VIP_ROLE_ID");
    private final String channelName = Config.require("TWITCH_CHANNEL");
    private final String publicUrl = Config.require("PUBLIC_URL").replaceAll("/+$", "");
    private final int port = Integer.parseInt(Config.require("WEB_PORT"));
    private final String redirectUri = publicUrl + "/callback";
    private final String userAgent = "DiscordBot (" + publicUrl + ", 1.0)";

    public WebServer(JDA jda, Twitch twitch, LinkStore links) {
        this.jda = jda;
        this.twitch = twitch;
        this.links = links;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleRoot);
        server.createContext("/login", this::handleLogin);
        server.createContext("/callback", this::handleCallback);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Web server listening on port " + port + " (public address: " + publicUrl + ")");
    }

    // ---------- pages ----------

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            respond(ex, 404, "Not found", "That page doesn't exist.");
            return;
        }
        respond(ex, 200, "Verification server", "The verification server is running.");
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        // The state is stored in a cookie and checked again on /callback (protects against forged logins).
        ex.getResponseHeaders().add("Set-Cookie",
                "oauth_state=" + state + "; Max-Age=600; Path=/; HttpOnly; Secure; SameSite=Lax");

        String url = "https://discord.com/oauth2/authorize"
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&scope=" + enc("identify connections")
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + state;
        redirect(ex, url);
    }

    private void handleCallback(HttpExchange ex) {
        try {
            process(ex);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                respond(ex, 500, "Something went wrong", "Please try again. If it keeps happening, tell a mod.");
            } catch (IOException ignored) {
                // connection already closed
            }
        }
    }

    // ---------- the actual verification ----------

    private void process(HttpExchange ex) throws Exception {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        // Clear the state cookie, it's single use.
        ex.getResponseHeaders().add("Set-Cookie", "oauth_state=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax");

        if (query.containsKey("error")) {
            respond(ex, 400, "Verification cancelled",
                    "You didn't allow the login. Press Verify in Discord again if you want to continue.");
            return;
        }

        String code = query.get("code");
        String state = query.get("state");
        String cookieState = readCookie(ex, "oauth_state");
        if (code == null || state == null || cookieState == null
                || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                cookieState.getBytes(StandardCharsets.UTF_8))) {
            respond(ex, 400, "Session expired", "Please go back to Discord and press Verify again.");
            return;
        }

        // 1. Swap the code for an access token
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);

        HttpRequest tokenReq = HttpRequest.newBuilder(URI.create(DISCORD_API + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();
        HttpResponse<String> tokenRes = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        if (tokenRes.statusCode() != 200) {
            System.err.println("[Web] Discord token exchange failed: " + tokenRes.statusCode() + " " + tokenRes.body());
            respond(ex, 502, "Login failed", "Discord rejected the login. Please try again, or tell a mod.");
            return;
        }
        String accessToken = JsonParser.parseString(tokenRes.body()).getAsJsonObject().get("access_token").getAsString();

        // 2. Who is this?
        JsonObject me = getJson("/users/@me", accessToken).getAsJsonObject();
        String discordId = me.get("id").getAsString();

        // 3. Find their verified Twitch connection
        JsonArray connections = getJson("/users/@me/connections", accessToken).getAsJsonArray();
        String twitchId = null;
        String twitchName = null;
        for (JsonElement el : connections) {
            JsonObject c = el.getAsJsonObject();
            boolean isTwitch = c.has("type") && "twitch".equals(c.get("type").getAsString());
            boolean verified = c.has("verified") && c.get("verified").getAsBoolean();
            if (isTwitch && verified) {
                twitchId = c.get("id").getAsString();
                twitchName = c.get("name").getAsString();
                break;
            }
        }
        if (twitchId == null) {
            respond(ex, 400, "No Twitch account found",
                    "Your Discord has no verified Twitch connection. In Discord go to User Settings > Connections, "
                            + "add Twitch, then press Verify again.");
            return;
        }
        if (!twitchId.matches("\\d+")) {
            twitchId = twitch.getUserId(twitchName); // fallback: look the ID up by name
            if (twitchId == null) {
                respond(ex, 400, "Twitch account not found", "I couldn't look up your Twitch account.");
                return;
            }
        }

        // 4. Are they in the server?
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) throw new IllegalStateException("The bot is not in the guild " + guildId);
        Member member;
        try {
            member = guild.retrieveMemberById(discordId).complete();
        } catch (ErrorResponseException e) {
            respond(ex, 403, "Join the server first", "You need to be in the Discord server before you can verify.");
            return;
        }

        // Remember who is linked to who (used by later features)
        links.put(discordId, twitchId, twitchName);

        // 5. Are they a VIP on Twitch?
        if (!twitch.isVip(twitchId)) {
            respond(ex, 403, "Not a VIP",
                    "Your Twitch account (" + twitchName + ") isn't a VIP on " + channelName + ".");
            return;
        }

        // 6. Give the Discord role
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            respond(ex, 500, "Setup problem", "The VIP role wasn't found. Please tell a mod to check VIP_ROLE_ID.");
            return;
        }
        try {
            guild.addRoleToMember(member, role).complete();
        } catch (Exception e) {
            e.printStackTrace();
            respond(ex, 500, "Couldn't give the role",
                    "A mod needs to move the bot's role above the VIP role in the server settings.");
            return;
        }

        respond(ex, 200, "You're verified!",
                "Welcome, " + twitchName + "! You now have the VIP role in Discord. You can close this tab.");
    }

    // ---------- helpers ----------

    private com.google.gson.JsonElement getJson(String path, String accessToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(DISCORD_API + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Discord API " + path + " returned " + res.statusCode() + ": " + res.body());
        }
        return JsonParser.parseString(res.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String encodeForm(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String key = i < 0 ? pair : pair.substring(0, i);
            String value = i < 0 ? "" : pair.substring(i + 1);
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String readCookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        }
        return null;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void redirect(HttpExchange ex, String url) throws IOException {
        ex.getResponseHeaders().set("Location", url);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void respond(HttpExchange ex, int status, String title, String message) throws IOException {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + "</title>"
                + "<style>"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:#18181b;color:#efeff1;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
                + ".card{max-width:420px;margin:16px;padding:32px;background:#26262c;border-radius:12px;"
                + "border-top:4px solid #9146ff;text-align:center}"
                + "h1{margin:0 0 12px;font-size:1.5rem}p{margin:0;line-height:1.5;color:#c9c9d1}"
                + "</style></head><body><div class=\"card\"><h1>" + esc(title) + "</h1><p>" + esc(message)
                + "</p></div></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
