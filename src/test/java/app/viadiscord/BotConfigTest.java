package app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotConfigTest {
    private static final String GUILD = "123456789012345678";
    @TempDir Path directory;

    @Test void missingTokenFailsWithoutNetworkOrSecretOutput() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> BotConfig.load(Map.of("DISCORD_GUILD_ID", GUILD), null));
        assertTrue(error.getMessage().contains("DISCORD_BOT_TOKEN"));
    }

    @Test void explicitFileAndEnvironmentOverrideAreSupported() throws Exception {
        Path file = directory.resolve("private.properties");
        Files.writeString(file, "discord.bot.token=file-token\ndiscord.guild.id=" + GUILD + "\n");
        var config = BotConfig.load(Map.of("DISCORD_BOT_TOKEN", "environment-token"), file);
        assertEquals("environment-token", config.token());
        assertEquals(GUILD, config.guildId());
        assertEquals(Duration.ofSeconds(60), config.startupTimeout());
        assertEquals(Path.of("state"), config.stateDirectory());
        assertFalse(config.toString().contains("environment-token"));
        assertFalse(config.toString().contains("file-token"));
    }

    @Test void blankEnvironmentDoesNotSilentlyUseAnotherFileToken() throws Exception {
        Path file = directory.resolve("private.properties");
        Files.writeString(file, "discord.bot.token=file-token\ndiscord.guild.id=" + GUILD + "\n");
        assertThrows(IllegalArgumentException.class,
                () -> BotConfig.load(Map.of("DISCORD_BOT_TOKEN", " "), file));
    }

    @Test void tokenWhitespaceIsRejectedWithoutEchoingTheValue() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> BotConfig.load(Map.of("DISCORD_BOT_TOKEN", "Bot private-value", "DISCORD_GUILD_ID", GUILD), null));
        assertFalse(error.getMessage().contains("private-value"));
    }

    @Test void malformedGuildsAreRejected() {
        for (String guild : new String[]{"", "abc", "123", "18446744073709551616"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> BotConfig.load(Map.of("DISCORD_BOT_TOKEN", "test-token", "DISCORD_GUILD_ID", guild), null));
        }
    }

    @Test void timeoutIsBoundedAndDefaultsAreExplicit() throws Exception {
        var config = BotConfig.load(Map.of("DISCORD_BOT_TOKEN", "test-token", "DISCORD_GUILD_ID", GUILD,
                "BOT_STARTUP_TIMEOUT_SECONDS", "15"), null);
        assertEquals(Duration.ofSeconds(15), config.startupTimeout());
        for (String timeout : new String[]{"-1", "4", "301", "forever"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> BotConfig.load(Map.of("DISCORD_BOT_TOKEN", "test-token", "DISCORD_GUILD_ID", GUILD,
                            "BOT_STARTUP_TIMEOUT_SECONDS", timeout), null));
        }
    }
    @Test void stateDirectoryDefaultsAndEnvironmentOverride() throws Exception {
        var defaults = Map.of("DISCORD_BOT_TOKEN", "test-token", "DISCORD_GUILD_ID", GUILD);
        assertEquals(Path.of("state").toAbsolutePath().normalize(), BotConfig.load(defaults, null).stateDirectory());
        Path file = directory.resolve("state.properties");
        Files.writeString(file, "bot.state.directory=file-state\n");
        var environment = new java.util.HashMap<>(defaults);
        environment.put("BOT_STATE_DIRECTORY", directory.resolve("persistent").toString());
        assertEquals(directory.resolve("persistent"), BotConfig.load(environment, file).stateDirectory());
        assertEquals(Path.of("file-state").toAbsolutePath().normalize(), BotConfig.load(defaults, file).stateDirectory());
    }
    @Test void stateDirectoryCanBeConfiguredOutsideTheRelease() throws Exception {
        var config = BotConfig.load(Map.of("DISCORD_BOT_TOKEN", "test-token", "DISCORD_GUILD_ID", GUILD,
                "BOT_STATE_DIRECTORY", directory.toString()), null);
        assertEquals(directory, config.stateDirectory());
    }
}
