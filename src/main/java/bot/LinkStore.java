package bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Remembers which Twitch account belongs to which Discord user (saved in links.json). */
public class LinkStore {
    public record Link(String twitchId, String twitchName) {}

    private static final Path FILE = Path.of("links.json");
    private static final Type TYPE = new TypeToken<Map<String, Link>>() {}.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Map<String, Link> links = new HashMap<>();

    public LinkStore() {
        try {
            if (Files.exists(FILE)) {
                Map<String, Link> loaded = gson.fromJson(Files.readString(FILE), TYPE);
                if (loaded != null) links = loaded;
            }
        } catch (Exception e) {
            System.out.println("[LinkStore] Could not read links.json, starting empty.");
        }
    }

    public synchronized void put(String discordId, String twitchId, String twitchName) {
        links.put(discordId, new Link(twitchId, twitchName));
        try {
            Files.writeString(FILE, gson.toJson(links, TYPE));
        } catch (IOException e) {
            System.err.println("[LinkStore] Could not save links.json: " + e.getMessage());
        }
    }

    public synchronized Link get(String discordId) {
        return links.get(discordId);
    }
}
