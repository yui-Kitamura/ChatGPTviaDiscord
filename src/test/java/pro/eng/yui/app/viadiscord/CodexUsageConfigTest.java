package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexUsageConfigTest {
    @TempDir Path directory;

    @Test void disabledByDefaultAndRequiresExplicitAbsolutePaths() throws Exception {
        assertFalse(CodexUsageConfig.load(Map.of(), null).enabled());
        assertThrows(IllegalArgumentException.class, () -> CodexUsageConfig.load(Map.of("CODEX_USAGE_ENABLED", "true"), null));
        assertThrows(IllegalArgumentException.class, () -> CodexUsageConfig.load(Map.of("CODEX_USAGE_ENABLED", "yes"), null));
        var config = enabled();
        assertTrue(CodexUsageConfig.load(config, null).enabled());
        config.put("CODEX_USAGE_HOME", "relative");
        assertThrows(IllegalArgumentException.class, () -> CodexUsageConfig.load(config, null));
    }

    @Test void fileSettingsAndEnvironmentPrecedenceAndBoundedTimeout() throws Exception {
        var file = directory.resolve("config.properties");
        var properties = new Properties();
        properties.setProperty("codex.usage.enabled", "true");
        properties.setProperty("codex.usage.executable", directory.resolve("codex").toString());
        properties.setProperty("codex.usage.home", directory.toString());
        properties.setProperty("codex.usage.timeout.seconds", "40");
        try (var writer = Files.newBufferedWriter(file)) { properties.store(writer, "test"); }
        assertEquals(40, CodexUsageConfig.load(Map.of(), file).timeout().toSeconds());
        assertFalse(CodexUsageConfig.load(Map.of("CODEX_USAGE_ENABLED", "false"), file).enabled());
        for (String value : new String[]{"0", "61", "secret-value"}) {
            var config = enabled(); config.put("CODEX_USAGE_TIMEOUT_SECONDS", value);
            var error = assertThrows(IllegalArgumentException.class, () -> CodexUsageConfig.load(config, null));
            assertFalse(error.getMessage().contains("secret-value"));
        }
    }

    private Map<String, String> enabled() {
        return new HashMap<>(Map.of("CODEX_USAGE_ENABLED", "true", "CODEX_USAGE_EXECUTABLE", directory.resolve("codex").toString(),
                "CODEX_USAGE_HOME", directory.toString()));
    }
}
