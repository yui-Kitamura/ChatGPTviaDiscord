package app.viadiscord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

record AiConfig(boolean enabled, String roleId, int callbackPort, Duration timeout) {
    static AiConfig load(Map<String, String> environment, Path file) throws IOException {
        var properties = new Properties();
        if (file != null) try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
        String role = environment.getOrDefault("DISCORD_AI_ROLE_ID", properties.getProperty("discord.ai.role.id", "")).trim();
        if (!role.isEmpty()) {
            try {
                if (!role.matches("[0-9]{17,20}") || Long.parseUnsignedLong(role) == 0) throw new NumberFormatException();
            } catch (NumberFormatException e) { throw new IllegalArgumentException("DISCORD_AI_ROLE_ID must be a role ID."); }
        }
        try {
            int port = Integer.parseInt(environment.getOrDefault("CHATGPT_CALLBACK_PORT", properties.getProperty("chatgpt.callback.port", "1455")));
            int seconds = Integer.parseInt(environment.getOrDefault("CHATGPT_REQUEST_TIMEOUT_SECONDS", properties.getProperty("chatgpt.request.timeout.seconds", "180")));
            if (port < 1024 || port > 65535 || seconds < 10 || seconds > 300) throw new NumberFormatException();
            String enabled = environment.getOrDefault("CHATGPT_ENABLED", properties.getProperty("chatgpt.enabled", "false"));
            if (!enabled.equals("true") && !enabled.equals("false")) throw new NumberFormatException();
            return new AiConfig(Boolean.parseBoolean(enabled), role, port, Duration.ofSeconds(seconds));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid ChatGPT callback port or request timeout."); }
    }
}
