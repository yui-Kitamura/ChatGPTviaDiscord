package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** An explicit destination opts in to automatic notifications. */
record LimitNotificationConfig(String channelId) {
    boolean enabled() { return !channelId.isEmpty(); }

    static LimitNotificationConfig load(Map<String, String> environment, Path file) throws IOException {
        var properties = new Properties();
        if (file != null) try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
        String channel = environment.getOrDefault("DISCORD_LIMIT_CHANNEL_ID",
                properties.getProperty("discord.limit.channel.id", "")).trim();
        try {
            if (!channel.isEmpty() && (!channel.matches("[0-9]{17,20}") || Long.parseUnsignedLong(channel) == 0)) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("DISCORD_LIMIT_CHANNEL_ID must be a numeric channel ID or empty to disable notifications.");
        }
        return new LimitNotificationConfig(channel);
    }
}
