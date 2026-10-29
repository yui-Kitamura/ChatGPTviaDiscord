package app.viadiscord;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/** Only an explicitly supplied file is read; environment values take priority. */
record BotConfig(String token, String guildId, Duration startupTimeout) {
    static BotConfig load(Map<String, String> environment, Path file) throws IOException {
        Properties properties = new Properties();
        if (file != null) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        String token = value(environment, properties, "DISCORD_BOT_TOKEN", "discord.bot.token");
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("DISCORD_BOT_TOKEN must contain a raw Bot token without whitespace.");
        }
        String guild = value(environment, properties, "DISCORD_GUILD_ID", "discord.guild.id");
        try {
            if (!guild.matches("[0-9]{17,20}") || Long.parseUnsignedLong(guild) == 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("DISCORD_GUILD_ID must be the target server's numeric ID.");
        }
        String seconds = value(environment, properties, "BOT_STARTUP_TIMEOUT_SECONDS", "bot.startup.timeout.seconds");
        int timeout = 60;
        if (!seconds.isBlank()) {
            try {
                timeout = Integer.parseInt(seconds);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("BOT_STARTUP_TIMEOUT_SECONDS must be an integer from 5 to 300.");
            }
        }
        if (timeout < 5 || timeout > 300) {
            throw new IllegalArgumentException("BOT_STARTUP_TIMEOUT_SECONDS must be an integer from 5 to 300.");
        }
        return new BotConfig(token, guild, Duration.ofSeconds(timeout));
    }

    private static String value(Map<String, String> environment, Properties properties, String name, String key) {
        return environment.getOrDefault(name, properties.getProperty(key, "")).trim();
    }

    @Override
    public String toString() {
        return "BotConfig[token=<redacted>, guildId=" + guildId + ", startupTimeout=" + startupTimeout + "]";
    }
}
