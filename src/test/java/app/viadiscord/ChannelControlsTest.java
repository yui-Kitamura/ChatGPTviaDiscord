package app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import net.dv8tion.jda.api.entities.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelControlsTest {
    static final String GUILD = "123456789012345678";
    static final String CHANNEL = "234567890123456789";
    static final String THREAD = "345678901234567890";
    static final String OTHER = "456789012345678901";
    @TempDir Path directory;

    private ChannelControls reopen() throws IOException {
        return new ChannelControls(GUILD, new ChannelSettings(directory.resolve("channel-response.properties")));
    }

    @Test void skipAndReadSurviveRestarts() throws Exception {
        var controls = reopen();
        assertTrue(controls.shouldRespond(GUILD, CHANNEL, null, false, false, MessageType.DEFAULT));
        assertTrue(controls.update(GUILD, "skipch", CHANNEL).contains("停止"));
        assertFalse(reopen().shouldRespond(GUILD, CHANNEL, null, false, false, MessageType.DEFAULT));
        assertTrue(reopen().update(GUILD, "readch", CHANNEL).contains("再開"));
        assertTrue(reopen().shouldRespond(GUILD, CHANNEL, null, false, false, MessageType.DEFAULT));
    }

    @Test void skipsThreadsOfSkippedParentsButDoesNotSkipTheParentForAThreadSetting() throws Exception {
        var controls = reopen();
        controls.update(GUILD, "skipch", CHANNEL);
        assertFalse(reopen().shouldRespond(GUILD, THREAD, CHANNEL, false, false, MessageType.DEFAULT));
        controls.update(GUILD, "readch", CHANNEL);
        controls.update(GUILD, "skipch", THREAD);
        assertFalse(reopen().shouldRespond(GUILD, THREAD, CHANNEL, false, false, MessageType.DEFAULT));
        assertTrue(reopen().shouldRespond(GUILD, CHANNEL, null, false, false, MessageType.DEFAULT));
    }

    @Test void onlyHumanDefaultMessagesAndRepliesAreAccepted() throws Exception {
        var controls = reopen();
        for (MessageType type : MessageType.values()) {
            assertEquals(type == MessageType.DEFAULT || type == MessageType.INLINE_REPLY,
                    controls.shouldRespond(GUILD, CHANNEL, null, false, false, type), type.name());
        }
        assertFalse(controls.shouldRespond(GUILD, CHANNEL, null, true, false, MessageType.DEFAULT));
        assertFalse(controls.shouldRespond(GUILD, CHANNEL, null, false, true, MessageType.DEFAULT));
        assertFalse(controls.shouldRespond(OTHER, CHANNEL, null, false, false, MessageType.DEFAULT));
        assertFalse(controls.shouldRespond(null, CHANNEL, null, false, false, MessageType.DEFAULT));
    }

    @Test void failedSaveDoesNotChangeThePolicyOrLeaveTemporaryFiles() throws Exception {
        var controls = reopen();
        Path destination = directory.resolve("channel-response.properties");
        Files.createDirectory(destination);
        Files.writeString(destination.resolve("keep"), "obstruct replacement");
        assertThrows(IOException.class, () -> controls.update(GUILD, "skipch", CHANNEL));
        assertTrue(controls.shouldRespond(GUILD, CHANNEL, null, false, false, MessageType.DEFAULT));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test void corruptStateDoesNotSilentlyEnableEveryChannel() throws Exception {
        Files.writeString(directory.resolve("channel-response.properties"),
                "version=1\nskip." + GUILD + "." + CHANNEL + "=not-a-boolean\n");
        assertThrows(IOException.class, this::reopen);
    }

    @Test void concurrentChangesDoNotLoseOtherChannels() throws Exception {
        var controls = reopen();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> controls.update(GUILD, "skipch", CHANNEL));
            var second = executor.submit(() -> controls.update(GUILD, "skipch", OTHER));
            first.get();
            second.get();
        }
        assertFalse(reopen().shouldRespond(GUILD, CHANNEL, null, false, false, MessageType.DEFAULT));
        assertFalse(reopen().shouldRespond(GUILD, OTHER, null, false, false, MessageType.DEFAULT));
    }

    @Test void unrelatedCommandsAndGuildsCannotChangeTheFile() throws Exception {
        var controls = reopen();
        assertThrows(IllegalArgumentException.class, () -> controls.update(OTHER, "skipch", CHANNEL));
        assertThrows(IllegalArgumentException.class, () -> controls.update(GUILD, "auth", CHANNEL));
        assertFalse(Files.exists(directory.resolve("channel-response.properties")));
    }
}
