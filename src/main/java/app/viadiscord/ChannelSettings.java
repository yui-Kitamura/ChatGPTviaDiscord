package app.viadiscord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Sparse skip list. Missing entries mean read/respond; failed writes change nothing in memory. */
final class ChannelSettings {
    private final Path file;
    private Set<String> skipped = new HashSet<>();

    ChannelSettings(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        if (!Files.exists(this.file)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid channel settings file.");
        }
        if (!"1".equals(properties.getProperty("version"))) throw new IOException("Unsupported channel settings format.");
        for (String key : properties.stringPropertyNames()) {
            if ("version".equals(key)) continue;
            String[] parts = key.split("\\.", -1);
            if (parts.length != 3 || !"skip".equals(parts[0]) || !validId(parts[1]) || !validId(parts[2])
                    || !"true".equals(properties.getProperty(key))) {
                throw new IOException("Invalid channel settings entry.");
            }
            skipped.add(parts[1] + "." + parts[2]);
        }
    }

    synchronized boolean isSkipped(String guild, String channel, String parent) {
        return skipped.contains(guild + "." + channel)
                || (parent != null && skipped.contains(guild + "." + parent));
    }

    synchronized void setSkipped(String guild, String channel, boolean skip) throws IOException {
        if (!validId(guild) || !validId(channel)) throw new IllegalArgumentException("Invalid channel identity.");
        Set<String> next = new HashSet<>(skipped);
        String key = guild + "." + channel;
        boolean changed = skip ? next.add(key) : next.remove(key);
        if (!changed) return;
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        for (String entry : next) properties.setProperty("skip." + entry, "true");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "channel-settings-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "ChatGPTviaDiscord channel response settings");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            skipped = next;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean validId(String id) {
        if (id == null || !id.matches("[0-9]{17,20}")) return false;
        try {
            return Long.parseUnsignedLong(id) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
