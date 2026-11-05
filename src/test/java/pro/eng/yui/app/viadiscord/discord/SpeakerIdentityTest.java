package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.eng.yui.app.viadiscord.data.local.ResponseSettings;

class SpeakerIdentityTest {

    @TempDir
    Path directory;
    static final String OWNER = "123456789012345678", TOP = "223456789012345678";

    static Role role(String id, String name) {
        var role = mock(Role.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        return role;
    }

    static Member member(Role... roles) {
        var member = mock(Member.class);
        when(member.getRoles()).thenReturn(List.of(roles));
        return member;
    }

    static JsonNode identity(Member member, String account, ResponseSettings settings) throws Exception {
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        String text = SpeakerIdentity.input(guild, member, account, settings, "hello").text();
        assertTrue(text.endsWith("\n[発言本文]\nhello"));
        return new ObjectMapper().readTree(text.split("\n")[1]);
    }

    @Test
    void ownerAccountsShareIdentityAndGuestIsSeparate() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        settings.setSpeakerRole("owner", OWNER);
        settings.setSpeakerRole("other", TOP);
        var owner = member(role(TOP, "top"), role(OWNER, "owner role"));
        var first = identity(owner, "account1", settings);
        var second = identity(owner, "account2", settings);
        var guest = identity(member(role(TOP, "top")), "guest", settings);
        assertEquals("guild:name:owner", first.get("identity").asText());
        assertEquals(first.get("identity"), second.get("identity"));
        assertNotEquals(first.get("accountId"), second.get("accountId"));
        assertNotEquals(first.get("identity"), guest.get("identity"));
    }

    @Test
    void registeredRolePrecedesHighestRoleAndSameNameCanCoverSeveralRoles() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        settings.setSpeakerRole("主たる発言者", OWNER);
        settings.setSpeakerRole("主たる発言者", TOP);
        var first = identity(member(role("323456789012345678", "higher"), role(OWNER, "low")), "one", settings);
        var second = identity(member(role(TOP, "another")), "two", settings);
        assertEquals(first.get("identity"), second.get("identity"));
        assertEquals("主たる発言者", first.get("name").asText());
    }

    @Test
    void unregisteredUsersUseHighestRoleThenAccountId() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        var guest = identity(member(role(TOP, "guest\"\nname"), role(OWNER, "lower")), "one", settings);
        assertEquals("guild:role:" + TOP, guest.get("identity").asText());
        assertEquals("guest\"\nname", guest.get("roleName").asText());
        assertEquals("guild:account:two", identity(member(), "two", settings).get("identity").asText());
        assertEquals("guild:account:three", identity(null, "three", settings).get("identity").asText());
    }

    static JsonNode mentions(String text) throws Exception {
        return new ObjectMapper().readTree(text.split("\\[ユーザーメンション情報\\]\\n", 2)[1].split("\n", 2)[0]);
    }

    @Test
    void ownerMentionsShareIdentityWithoutChangingTheGuestSpeakerOrBody() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        settings.setSpeakerRole("owner", OWNER);
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        var owner = member(role(TOP, "higher"), role(OWNER, "owner"));
        String first = "323456789012345678", second = "423456789012345678";
        when(guild.getMemberById(first)).thenReturn(owner);
        when(guild.getMemberById(second)).thenReturn(owner);
        String body = "<@" + first + "> <@!" + first + "> と <@" + second + "> の話";
        String input = SpeakerIdentity.input(guild, member(), "guest", settings, body).text();
        var data = mentions(input);
        assertEquals(2, data.get("users").size());
        assertEquals(0, data.get("omitted").asInt());
        for (var user : data.get("users")) {
            assertEquals("guild:name:owner", user.get("identity").asText());
            assertEquals("resolved", user.get("memberStatus").asText());
        }
        assertNotEquals(data.get("users").get(0).get("accountId"), data.get("users").get(1).get("accountId"));
        assertEquals("guild:account:guest", new ObjectMapper().readTree(input.split("\n")[1]).get("identity").asText());
        assertTrue(input.endsWith("[発言本文]\n" + body));
        verify(guild, never()).retrieveMemberById(anyString());
    }

    @Test
    void cacheMissRetrievesMemberButFailureFallsBackToAccountId() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        settings.setSpeakerRole("owner", OWNER);
        var guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        when(guild.getId()).thenReturn("guild");
        String known = "323456789012345678", missing = "423456789012345678";
        when(guild.getMemberById(anyString())).thenReturn(null);
        var request = guild.retrieveMemberById(known);
        when(request.timeout(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(request);
        var owner = member(role(OWNER, "owner"));
        when(request.complete()).thenReturn(owner);
        when(guild.retrieveMemberById(missing)).thenThrow(new IllegalStateException("unavailable"));
        var data = mentions(SpeakerIdentity.input(guild, member(), "speaker", settings, "<@" + known + "> <@" + missing + ">").text());
        assertEquals("guild:name:owner", data.get("users").get(0).get("identity").asText());
        var unavailable = data.get("users").get(1);
        assertEquals("guild:account:" + missing, unavailable.get("identity").asText());
        assertEquals("unavailable", unavailable.get("memberStatus").asText());
        assertFalse(unavailable.has("roleId"));
        assertFalse(unavailable.has("name"));
    }

    @Test
    void mentionTargetsUseSameRoleAndAccountFallbacksAsSpeakers() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        String guest = "323456789012345678", noRole = "423456789012345678";
        var guestMember = member(role(TOP, "guest\"\nrole"));
        var noRoleMember = member();
        when(guild.getMemberById(guest)).thenReturn(guestMember);
        when(guild.getMemberById(noRole)).thenReturn(noRoleMember);
        var data = mentions(SpeakerIdentity.input(guild, member(), "speaker", settings, "<@" + guest + "> <@" + noRole + ">").text());
        assertEquals(identity(guestMember, guest, settings).get("identity"), data.get("users").get(0).get("identity"));
        assertEquals(identity(noRoleMember, noRole, settings).get("identity"), data.get("users").get(1).get("identity"));
        assertEquals("guest\"\nrole", data.get("users").get(0).get("roleName").asText());
    }

    @Test
    void manyMentionsAreBoundedAndRoleOrChannelMentionsAreNotUsers() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        var cached = member();
        when(guild.getMemberById(anyString())).thenReturn(cached);
        var body = new StringBuilder();
        for (int i=0; i<23; i++) {
            body.append("<@").append(400000000000000000L + i).append(">");
        }
        var data = mentions(SpeakerIdentity.input(guild, member(), "speaker", settings, body.toString()).text());
        assertEquals(20, data.get("users").size());
        assertEquals(3, data.get("omitted").asInt());
        verify(guild, times(20)).getMemberById(anyString());
        verify(guild, never()).retrieveMemberById(anyString());
        clearInvocations(guild);
        String nonUsers = "<@&" + OWNER + "> <#" + TOP + "> @everyone @owner";
        assertFalse(SpeakerIdentity.input(guild, member(), "speaker", settings, nonUsers).text().contains("[ユーザーメンション情報]"));
        verify(guild, never()).getMemberById(anyString());
    }
}
