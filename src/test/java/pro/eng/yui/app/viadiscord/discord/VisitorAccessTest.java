package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.eng.yui.app.viadiscord.data.local.ResponseSettings;
import pro.eng.yui.app.viadiscord.domain.model.CharacterSpec;

public class VisitorAccessTest {

    @TempDir
    Path directory;
    static final String USER = "123456789012345678", OTHER = "223456789012345678";

    public static net.dv8tion.jda.api.entities.Member visitor() {
        var member = AiAccessTest.member(false, USER);
        when(member.getRoles().getFirst().getName()).thenReturn("GvDvisitor");
        return member;
    }

    @Test
    void requiresConfiguredExactChannelRoleAndExplicitMention() throws Exception {
        var access = new VisitorAccess(directory);
        var settings = new ResponseSettings(directory.resolve("response.properties"));
        var h = new GvdListenerTest();
        h.directory = directory;
        h.setUp();
        var member = visitor();
        h.mentionBot("<@" + GvdListenerTest.BOT + ">");
        assertFalse(access.permits(h.messageEvent, member, settings));
        access.setChannel(ChannelControlsTest.CHANNEL);
        assertTrue(access.permits(h.messageEvent, member, settings));
        when(h.message.getContentRaw()).thenReturn("implicit reply");

        assertFalse(access.permits(h.messageEvent, member, settings));
        h.mentionBot("<@!" + GvdListenerTest.BOT + ">");

        assertTrue(access.permits(h.messageEvent, member, settings));
        when(member.getRoles().getFirst().getName()).thenReturn("GvDvisitor extra");

        assertFalse(access.permits(h.messageEvent, member, settings));
        when(member.getRoles().getFirst().getName()).thenReturn("GvDvisitor");
        when(member.getUser().isBot()).thenReturn(true);

        assertFalse(access.permits(h.messageEvent, member, settings));
        assertFalse(access.permits(h.messageEvent, null, settings));
        when(member.getUser().isBot()).thenReturn(false);
        when(h.messageEvent.getChannel().getId()).thenReturn(ChannelControlsTest.THREAD);

        assertFalse(access.permits(h.messageEvent, member, settings));
        when(h.messageEvent.getChannel().getId()).thenReturn(ChannelControlsTest.CHANNEL);
        access.setChannel(ChannelControlsTest.THREAD);
        access = new VisitorAccess(directory);
        assertTrue(access.permits(h.messageEvent, member, settings));
        when(h.messageEvent.getChannel().getId()).thenReturn(ChannelControlsTest.THREAD);
        assertTrue(access.permits(h.messageEvent, member, settings));
        when(h.messageEvent.getChannel().getId()).thenReturn(OTHER);
        assertFalse(access.permits(h.messageEvent, member, settings));
    }

    @Test
    void onlySavedGeneratedRoleIdsGrantTheException() throws Exception {
        var access = new VisitorAccess(directory);
        access.setChannel(ChannelControlsTest.CHANNEL);
        var settings = new ResponseSettings(directory.resolve("response.properties"));
        var h = new GvdListenerTest();
        h.directory = directory;
        h.setUp();
        var role = mock(Role.class);
        when(role.getId()).thenReturn(OTHER);
        when(role.getName()).thenReturn("GvD: guide");
        when(h.message.getMentions().getRoles()).thenReturn(List.of(role));
        assertFalse(access.permits(h.messageEvent, visitor(), settings));
        settings.putCharacter(new CharacterSpec("guide", "guide"), OTHER);
        assertTrue(access.permits(h.messageEvent, visitor(), settings));
        when(role.getId()).thenReturn(USER);

        assertFalse(access.permits(h.messageEvent, visitor(), settings));
    }

    static Clock at(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    @Test
    void independentSixtySecondSlotsSurviveRestartAndChannelChange() throws Exception {
        var access = new VisitorAccess(directory, at(100_000));
        access.setChannel(ChannelControlsTest.CHANNEL);
        assertTrue(access.acquire(USER));
        assertFalse(access.acquire(USER));
        assertTrue(access.acquire(OTHER));
        access = new VisitorAccess(directory, at(159_999));
        access.setChannel(ChannelControlsTest.THREAD);

        assertFalse(access.acquire(USER));
        assertFalse(new VisitorAccess(directory, at(90_000)).acquire(USER));
        assertTrue(new VisitorAccess(directory, at(160_000)).acquire(USER));
        assertFalse(new VisitorAccess(directory, at(160_000)).acquire(USER));
    }

    @Test
    void saveFailureDoesNotGrantOrConsumeAndCorruptStateFailsClosed() throws Exception {
        var access = new VisitorAccess(directory, at(100_000));
        Files.createDirectory(directory.resolve("ai-visitor.properties"));
        assertThrows(java.io.IOException.class, () -> access.setChannel(ChannelControlsTest.CHANNEL));
        assertThrows(java.io.IOException.class, () -> access.acquire(USER));
        Files.delete(directory.resolve("ai-visitor.properties"));
        assertTrue(access.acquire(USER));
        Files.writeString(directory.resolve("ai-visitor.properties"), "last." + USER + "=invalid");
        assertThrows(java.io.IOException.class, () -> new VisitorAccess(directory));
    }

    @Test
    void clearingDisablesTheExceptionAcrossRestartAndRetainsRateState() throws Exception {
        var access = new VisitorAccess(directory, at(100_000));
        var settings = new ResponseSettings(directory.resolve("response.properties"));
        var h = new GvdListenerTest();
        h.directory = directory;
        h.setUp();
        h.mentionBot("<@" + GvdListenerTest.BOT + ">");
        var member = visitor();
        access.setChannel(ChannelControlsTest.CHANNEL);
        assertTrue(access.permits(h.messageEvent, member, settings));
        assertTrue(access.acquire(USER));
        access.setChannel(ChannelControlsTest.THREAD);
        access.clearChannel(ChannelControlsTest.CHANNEL);
        assertFalse(access.permits(h.messageEvent, member, settings));
        access = new VisitorAccess(directory, at(100_001));

        assertFalse(access.permits(h.messageEvent, member, settings));
        assertTrue(access.isChannel(ChannelControlsTest.THREAD));
        // Idempotent when not configured.
        access.clearChannel(ChannelControlsTest.CHANNEL);

        access.setChannel(ChannelControlsTest.CHANNEL);

        assertTrue(access.permits(h.messageEvent, member, settings));
        assertFalse(access.acquire(USER));
        access.clearChannel(ChannelControlsTest.THREAD);
        access.clearChannel(ChannelControlsTest.CHANNEL);
        access = new VisitorAccess(directory, at(100_001));
        assertFalse(access.isChannel(ChannelControlsTest.CHANNEL));
        assertFalse(access.isChannel(ChannelControlsTest.THREAD));
        assertFalse(access.acquire(USER));
    }

    @Test
    void failedClearRetainsTheConfiguredChannel() throws Exception {
        var access = new VisitorAccess(directory);
        access.setChannel(ChannelControlsTest.CHANNEL);
        var settings = new ResponseSettings(directory.resolve("response.properties"));
        var h = new GvdListenerTest();
        h.directory = directory;
        h.setUp();
        h.mentionBot("<@" + GvdListenerTest.BOT + ">");
        Files.delete(directory.resolve("ai-visitor.properties"));
        Files.createDirectory(directory.resolve("ai-visitor.properties"));
        assertThrows(java.io.IOException.class, () -> access.clearChannel(ChannelControlsTest.CHANNEL));
        assertTrue(access.permits(h.messageEvent, visitor(), settings));
    }

    @Test
    void legacyChannelMigratesWithoutLosingRateStateOrReplacingOtherChannels() throws Exception {
        var file = directory.resolve("ai-visitor.properties");
        Files.writeString(file, "channel="+ ChannelControlsTest.CHANNEL +"\nlast."+ USER +"=100000\n");
        var access = new VisitorAccess(directory, at(100_001));
        assertTrue(access.isChannel(ChannelControlsTest.CHANNEL));
        assertFalse(access.acquire(USER));
        access.setChannel(ChannelControlsTest.THREAD);
        access.setChannel(ChannelControlsTest.THREAD);
        access = new VisitorAccess(directory, at(100_001));
        assertTrue(access.isChannel(ChannelControlsTest.CHANNEL));
        assertTrue(access.isChannel(ChannelControlsTest.THREAD));
        assertFalse(Files.readString(file).contains("channel="));
        assertFalse(access.acquire(USER));
        access.clearChannel(ChannelControlsTest.THREAD);
        access.setChannel(ChannelControlsTest.THREAD);
        assertFalse(access.acquire(USER));
        access.clearChannel(ChannelControlsTest.CHANNEL);
        access = new VisitorAccess(directory, at(100_001));
        assertFalse(access.isChannel(ChannelControlsTest.CHANNEL));
        assertTrue(access.isChannel(ChannelControlsTest.THREAD));
    }

    @Test
    void invalidChannelEntriesFailClosedAndInvalidUpdatesLeaveExistingChannels() throws Exception {
        var file = directory.resolve("ai-visitor.properties");
        for(String value : List.of("channel.invalid=true", "channel."+ USER +"=false", "channel=invalid")) {
            Files.writeString(file, value);
            assertThrows(java.io.IOException.class, () -> new VisitorAccess(directory));
        }
        Files.delete(file);
        var access = new VisitorAccess(directory);
        access.setChannel(ChannelControlsTest.CHANNEL);
        assertThrows(java.io.IOException.class, () -> access.clearChannel("invalid"));
        assertThrows(java.io.IOException.class, () -> access.setChannel("invalid"));
        assertTrue(new VisitorAccess(directory).isChannel(ChannelControlsTest.CHANNEL));
    }

    @Test
    void simultaneousRequestsHaveOnlyOneWinner() throws Exception {
        var access = new VisitorAccess(directory, at(100_000));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i=0; i<8; i++) {
                tasks.add(executor.submit(() -> access.acquire(USER)));
            }
            int accepted = 0;
            for (var task : tasks) {
                if (task.get()) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
        }
    }
}
