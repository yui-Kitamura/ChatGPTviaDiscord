package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import net.dv8tion.jda.api.entities.Member;
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
        String text = SpeakerIdentity.input("guild", member, account, settings, "hello");
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
}
