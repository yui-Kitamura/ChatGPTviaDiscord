package app.viadiscord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** One configured guild. Updates become visible only after atomic persistence succeeds. */
final class ResponseSettings {
    record CharacterRole(CharacterSpec character, String roleId) {}
    private final Path file;
    private Properties data = new Properties();

    ResponseSettings(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { data.load(reader); }
            for (String key : data.stringPropertyNames()) {
                if (key.startsWith("mode.")) ResponseMode.parse(data.getProperty(key));
            }
            characters(); // Fail startup on malformed character state; never silently discard it.
        }
    }

    synchronized ResponseMode mode(String channelId) {
        return ResponseMode.parse(data.getProperty("mode." + channelId, "qa"));
    }

    synchronized void setMode(String channelId, ResponseMode mode) throws IOException {
        var updated = copy();
        updated.setProperty("mode." + channelId, mode.value());
        save(updated);
    }

    synchronized List<CharacterRole> characters() {
        var result = new ArrayList<CharacterRole>();
        for (String key : data.stringPropertyNames().stream().sorted().toList()) {
            if (!key.startsWith("character.") || !key.endsWith(".name")) continue;
            String id = key.substring("character.".length(), key.length() - ".name".length());
            if (!id.matches("[0-9]{17,20}")) throw new IllegalArgumentException("Invalid stored role ID.");
            result.add(new CharacterRole(new CharacterSpec(data.getProperty(key),
                    data.getProperty("character." + id + ".persona")), id));
        }
        return List.copyOf(result);
    }

    synchronized void putCharacter(CharacterSpec character, String roleId) throws IOException {
        if (!roleId.matches("[0-9]{17,20}")) throw new IllegalArgumentException("Invalid role ID.");
        var updated = copy();
        for (var existing : characters()) {
            if (existing.character().name().equals(character.name())) {
                updated.remove("character." + existing.roleId() + ".name");
                updated.remove("character." + existing.roleId() + ".persona");
            }
        }
        updated.setProperty("character." + roleId + ".name", character.name());
        updated.setProperty("character." + roleId + ".persona", character.persona());
        save(updated);
    }

    private Properties copy() {
        var result = new Properties();
        result.putAll(data);
        return result;
    }

    private void save(Properties updated) throws IOException {
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".response-settings-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                updated.store(writer, "GvD response settings and generated AI characters");
            }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            data = updated;
        } finally { Files.deleteIfExists(temporary); }
    }
}
