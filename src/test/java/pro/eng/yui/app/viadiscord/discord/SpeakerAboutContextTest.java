package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.eng.yui.app.viadiscord.data.local.ResponseSettings;

public class SpeakerAboutContextTest {

    @TempDir
    Path directory;
    static final String OWNER = "123456789012345678", ABOUT = "223456789012345678";

    public static RestAction<List<Message>> document(Guild guild, String id, String text) {
        var request = ServerModelContextTest.document(guild, "profile", ServerModelContextTest.message(text));
        var channel = guild.getTextChannelsByName("profile", false).getFirst();
        when(channel.getId()).thenReturn(id);
        when(guild.getTextChannelById(id)).thenReturn(channel);
        return request;
    }

    @Test
    void onlyRelevantPeopleGetProfilesAndSameIdentityIsReadOnceThenRefreshed() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        settings.setSpeakerRole("owner", OWNER, ABOUT);
        settings.setSpeakerRole("unrelated", "323456789012345678", "423456789012345678");
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        var owner = SpeakerIdentityTest.member(SpeakerIdentityTest.role(OWNER, "owner role"));
        var request = document(guild, ABOUT, "owner biography");
        var ownerInput = SpeakerIdentity.input(guild, owner, "account", settings, "hello");
        assertTrue(ownerInput.aboutContext().contains("owner biography"));
        assertTrue(ownerInput.aboutContext().contains("guild:name:owner"));
        assertFalse(ownerInput.text().contains("owner biography"));
        assertEquals("", SpeakerIdentity.input(guild, SpeakerIdentityTest.member(), "guest", settings, "hello").aboutContext());
        verify(guild, never()).getTextChannelById("423456789012345678");

        clearInvocations(request);
        String alias = "523456789012345678";
        when(guild.getMemberById(alias)).thenReturn(owner);
        var mentions = SpeakerIdentity.input(guild, owner, "account", settings, "<@" + alias + ">");
        assertEquals(1, mentions.aboutContext().split("owner biography", -1).length - 1);
        verify(request, times(1)).complete();
        var updatedMessage = ServerModelContextTest.message("updated biography");
        when(request.complete()).thenReturn(List.of(updatedMessage));
        var updated = SpeakerIdentity.input(guild, owner, "account", settings, "hello");
        assertTrue(updated.aboutContext().contains("updated biography"));
        assertFalse(updated.aboutContext().contains("owner biography"));
    }

    @Test
    void guestMentionCanReferenceOwnerProfileWithoutBecomingOwner() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        settings.setSpeakerRole("owner", OWNER, ABOUT);
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        var owner = SpeakerIdentityTest.member(SpeakerIdentityTest.role(OWNER, "owner"));
        String account = "323456789012345678";
        when(guild.getMemberById(account)).thenReturn(owner);
        document(guild, ABOUT, "owner biography");
        var input = SpeakerIdentity.input(guild, SpeakerIdentityTest.member(), "guest", settings, "<@" + account + ">");
        assertTrue(input.text().startsWith("[発言者情報]\n{\"identity\":\"guild:account:guest\""));
        assertTrue(input.aboutContext().contains("guild:name:owner"));
        assertTrue(input.aboutContext().contains("owner biography"));
    }

    @Test
    void missingEmptyAndOversizedBoundProfilesStopTheCall() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        settings.setSpeakerRole("owner", OWNER, ABOUT);
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        var owner = SpeakerIdentityTest.member(SpeakerIdentityTest.role(OWNER, "owner"));
        assertThrows(ServerModelContext.Unavailable.class, () -> SpeakerIdentity.input(guild, owner, "account", settings, "hello"));
        document(guild, ABOUT, " ");
        assertThrows(ServerModelContext.Unavailable.class, () -> SpeakerIdentity.input(guild, owner, "account", settings, "hello"));
        document(guild, ABOUT, "x".repeat(32_000));
        assertThrows(ServerModelContext.Unavailable.class, () -> SpeakerIdentity.input(guild, owner, "account", settings, "hello"));
    }
}
