package bot;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/** Reads values from config.properties, falling back to environment variables. */
public final class Config {
    private static final Properties props = new Properties();

    static {
        try (FileInputStream in = new FileInputStream("config.properties")) {
            props.load(in);
        } catch (IOException e) {
            System.out.println("[Config] config.properties not found, using environment variables.");
        }
    }

    private Config() {}

    public static String get(String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    public static String require(String key) {
        String value = get(key);
        if (value.isEmpty()) throw new IllegalStateException("Missing value in config.properties: " + key);
        return value;
    }

    public static boolean getBool(String key) {
        return get(key).equalsIgnoreCase("true");
    }
}
