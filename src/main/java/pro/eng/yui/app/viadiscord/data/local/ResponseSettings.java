package pro.eng.yui.app.viadiscord.data.local;

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
import pro.eng.yui.app.viadiscord.domain.model.CharacterSpec;
import pro.eng.yui.app.viadiscord.domain.model.ResponseMode;

/**
 * One configured guild. Updates become visible only after atomic persistence succeeds.
 */
public final class ResponseSettings {

    public record CharacterRole(CharacterSpec character, String roleId) {
    }

    private final Path file;
    private Properties data = new Properties();

    public ResponseSettings(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                data.load(reader);
            }
            for (String key : data.stringPropertyNames()) {
                if (key.startsWith("mode.")) {
                    ResponseMode.parse(data.getProperty(key));
                }
                if (key.startsWith("speaker.")) {
                    validateSpeakerRole(data.getProperty(key), key.substring("speaker.".length()));
                }
                if (key.startsWith("speaker-about.")) {
                    validateSpeakerRole("about", key.substring("speaker-about.".length()));
                    validateAboutChannel(data.getProperty(key));
                }
            }
            // Fail startup on malformed character state; never silently discard it.
            characters();
        }
    }

    public synchronized String speakerName(String roleId) {
        return data.getProperty("speaker." + roleId, "");
    }

    public synchronized void setSpeakerRole(String name, String roleId) throws IOException {
        setSpeakerRole(name, roleId, null);
    }

    public synchronized String speakerAbout(String roleId) {
        return data.getProperty("speaker-about." + roleId, "");
    }

    public synchronized void setSpeakerRole(String name, String roleId, String aboutChannelId) throws IOException {
        validateSpeakerRole(name, roleId);
        var updated = copy();
        updated.setProperty("speaker." + roleId, name);
        if (aboutChannelId != null) {
            validateAboutChannel(aboutChannelId);
            updated.setProperty("speaker-about." + roleId, aboutChannelId);
        }
        save(updated);
    }

    private static void validateAboutChannel(String channelId) {
        if (channelId.matches("[0-9]{17,20}") == false) {
            throw new IllegalArgumentException("Invalid about channel ID.");
        }
    }

    private static void validateSpeakerRole(String name, String roleId) {
        if (roleId.matches("[0-9]{17,20}") == false || name.isBlank() || name.length() > 100 ||
            name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid speaker name or role ID.");
        }
    }

    public synchronized ResponseMode mode(String channelId) {
        return ResponseMode.parse(data.getProperty("mode." + channelId, "qa"));
    }

    public synchronized void setMode(String channelId, ResponseMode mode) throws IOException {
        var updated = copy();
        updated.setProperty("mode." + channelId, mode.value());
        save(updated);
    }

    public synchronized List<CharacterRole> characters() {
        var result = new ArrayList<CharacterRole>();
        for (String key : data.stringPropertyNames().stream().sorted().toList()) {
            if (!key.startsWith("character.") || !key.endsWith(".name")) {
                continue;
            }

            String id = key.substring("character.".length(), key.length() - ".name".length());
            if (id.matches("[0-9]{17,20}") == false) {
                throw new IllegalArgumentException("Invalid stored role ID.");
            }

            result.add(
                new CharacterRole(
                    new CharacterSpec(data.getProperty(key), data.getProperty("character." + id + ".persona")),
                    id
                )
            );
        }
        return List.copyOf(result);
    }

    public synchronized void putCharacter(CharacterSpec character, String roleId) throws IOException {
        if (roleId.matches("[0-9]{17,20}") == false) {
            throw new IllegalArgumentException("Invalid role ID.");
        }

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
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
