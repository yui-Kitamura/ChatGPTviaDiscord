package app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.dv8tion.jda.api.entities.*;

class AiAccessTest {
    @TempDir Path directory;
    static final String ROLE="1565364891676713192",OTHER="123456789012345678";
    static Member member(boolean bot,String... roles) {
        var user=mock(User.class);when(user.isBot()).thenReturn(bot);var member=mock(Member.class);when(member.getUser()).thenReturn(user);
        var items=Arrays.stream(roles).map(id->{var role=mock(Role.class);when(role.getId()).thenReturn(id);return role;}).toList();
        when(member.getRoles()).thenReturn(items);return member;
    }
    @Test void multipleRolesPersistAndRejectRemovesOnlyOneGrant() throws Exception {
        var access=new AiAccess(directory,"");assertFalse(access.permits(member(false,ROLE)));
        access.update(ROLE,true);access.update(OTHER,true);assertTrue(access.permits(member(false,ROLE)));assertTrue(access.permits(member(false,OTHER)));
        access=new AiAccess(directory,"");assertEquals(Set.of(ROLE,OTHER),access.roleIds());
        access.update(ROLE,false);assertFalse(access.permits(member(false,ROLE)));assertTrue(access.permits(member(false,ROLE,OTHER)));
        assertFalse(access.permits(member(true,OTHER)));assertFalse(access.permits(null));
        assertEquals(Set.of(OTHER),new AiAccess(directory,"").roleIds());
    }
    @Test void failedSaveDoesNotGrantAccess() throws Exception {
        var access=new AiAccess(directory,"");Files.createDirectory(directory.resolve("ai-role.txt"));Files.writeString(directory.resolve("ai-role.txt/obstacle"),"x");
        assertThrows(java.io.IOException.class,()->access.update(ROLE,true));assertFalse(access.permits(member(false,ROLE)));
    }
    @Test void disabledByDefaultAndConfigurationBoundsAreChecked() throws Exception {
        assertFalse(AiConfig.load(Map.of(),null).enabled());assertEquals(1455,AiConfig.load(Map.of(),null).callbackPort());
        assertTrue(AiConfig.load(Map.of("CHATGPT_ENABLED","true"),null).enabled());
        assertThrows(IllegalArgumentException.class,()->AiConfig.load(Map.of("CHATGPT_CALLBACK_PORT","80"),null));
        assertThrows(IllegalArgumentException.class,()->AiConfig.load(Map.of("CHATGPT_ENABLED","perhaps"),null));
    }
    @Test void recentHistorySurvivesRestartAndKeepsUserAndAssistantSeparate() throws Exception {
        var store=new ChatHistory(directory);var history=store.load("123-abc");history.turns.add(Map.of("role","user","content","original"));history.turns.add(Map.of("role","assistant","content","proposal"));history.ids.add("123");store.save("123-abc",history);
        var restored=new ChatHistory(directory).load("123-abc");assertEquals(history.turns,restored.turns);assertEquals(history.ids,restored.ids);
        assertThrows(IllegalArgumentException.class,()->store.load("../escape"));
    }
}
