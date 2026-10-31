package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LimitNotificationConfigTest {
    @TempDir Path directory;
    @Test void defaultsToDisabledAndEnvironmentOverridesFile() throws Exception {
        assertFalse(LimitNotificationConfig.load(Map.of(),null).enabled());
        var file=directory.resolve("config.properties");
        Files.writeString(file,"discord.limit.channel.id=123456789012345678\n");
        assertEquals("123456789012345678",LimitNotificationConfig.load(Map.of(),file).channelId());
        assertEquals("234567890123456789",LimitNotificationConfig.load(Map.of("DISCORD_LIMIT_CHANNEL_ID"," 234567890123456789 "),file).channelId());
        assertFalse(LimitNotificationConfig.load(Map.of("DISCORD_LIMIT_CHANNEL_ID",""),file).enabled());
    }
    @Test void rejectsNamesMalformedAndOverflowWithoutEchoingValues() {
        for(String value:new String[]{"#limit","123","-123456789012345678","18446744073709551616"}) {
            var error=assertThrows(IllegalArgumentException.class,()->LimitNotificationConfig.load(Map.of("DISCORD_LIMIT_CHANNEL_ID",value),null));
            assertFalse(error.getMessage().contains(value));
        }
    }
}
