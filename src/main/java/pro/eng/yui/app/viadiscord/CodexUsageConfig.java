package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/** Opt-in, separate from the credentials used for inference. */
record CodexUsageConfig(boolean enabled, Path executable, Path home, Duration timeout) {
    static CodexUsageConfig load(Map<String, String> environment, Path file) throws IOException {
        var properties = new Properties();
        if (file != null) try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
        String enabled = value(environment, properties, "CODEX_USAGE_ENABLED", "codex.usage.enabled", "false");
        if (!enabled.equals("true") && !enabled.equals("false")) throw invalid();
        if (!Boolean.parseBoolean(enabled)) return new CodexUsageConfig(false, null, null, Duration.ofSeconds(20));
        try {
            String executable = value(environment, properties, "CODEX_USAGE_EXECUTABLE", "codex.usage.executable", "");
            String home = value(environment, properties, "CODEX_USAGE_HOME", "codex.usage.home", "");
            int seconds = Integer.parseInt(value(environment, properties, "CODEX_USAGE_TIMEOUT_SECONDS", "codex.usage.timeout.seconds", "20"));
            if (executable.isBlank() || home.isBlank() || seconds < 1 || seconds > 60) throw invalid();
            Path binary = Path.of(executable), directory = Path.of(home);
            if (!binary.isAbsolute() || !directory.isAbsolute()) throw invalid();
            return new CodexUsageConfig(true, binary.normalize(), directory.normalize(), Duration.ofSeconds(seconds));
        } catch (IllegalArgumentException e) { throw invalid(); }
    }

    private static String value(Map<String, String> environment, Properties properties, String env, String key, String fallback) {
        return environment.getOrDefault(env, properties.getProperty(key, fallback)).trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Codex usage settings: use true/false, absolute executable/home paths, and a timeout of 1-60 seconds.");
    }
}
