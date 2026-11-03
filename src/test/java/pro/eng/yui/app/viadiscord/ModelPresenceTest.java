package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.managers.Presence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelPresenceTest {
    @TempDir Path directory;
    private static final String GUILD = "12345678901234567";

    private static ChatGptAccounts.Account account(String model, String name) {
        var account = new ChatGptAccounts.Account();
        account.accessToken = "test-token";
        account.model = model;
        account.modelName = name;
        return account;
    }

    @Test void usesCatalogDisplayNameAndClearsForOtherFamiliesOrDisconnectedAccounts() {
        var account = account("gpt-example", "GPT Example");
        assertEquals("GPT Example", ModelPresence.activity(account).getName());
        assertEquals(Activity.ActivityType.CUSTOM_STATUS, ModelPresence.activity(account).getType());
        account.model = "o3";
        assertNull(ModelPresence.activity(account));
        account.model = "gpt-example"; account.accessToken = "";
        assertNull(ModelPresence.activity(account));
        assertNull(ModelPresence.activity(null));
        assertNull(ModelPresence.activity(account("gpt-example", "")));
        assertNull(ModelPresence.activity(account("", "GPT Example")));
    }

    @Test void restoresLegacySelectionFromCatalogThenTracksModelAndAccountChanges() throws Exception {
        try (var accounts = new ChatGptAccounts(directory)) {
            String key = ChatGptAccounts.key(GUILD, "default");
            var legacy = account("gpt-example", "");
            legacy.scope = "chatgpt.tokens.use.direct";
            legacy.expiresAt = Long.MAX_VALUE;
            accounts.put(key, legacy, true);
            var transport = mock(OpenAiTransport.class);
            when(transport.models("test-token")).thenReturn(List.of(new OpenAiTransport.Model("gpt-example", "GPT Example")));
            var jda = mock(JDA.class);
            var presence = mock(Presence.class);
            when(jda.getPresence()).thenReturn(presence);
            try (var session = new ChatGptSession(accounts, transport, 0);
                 var modelPresence = new ModelPresence(GUILD, accounts, session, transport)) {
                modelPresence.refresh(jda);
                verify(presence).setActivity(Activity.customStatus("GPT Example"));
                when(presence.getActivity()).thenReturn(Activity.customStatus("GPT Example"));
                modelPresence.refresh(jda);
                verify(transport, times(1)).models("test-token");
                verify(presence, times(1)).setActivity(any());
                accounts.put(key, account("gpt-other", "GPT Other"), false);
                modelPresence.refresh(jda);
                verify(presence).setActivity(Activity.customStatus("GPT Other"));
                String other = ChatGptAccounts.key(GUILD, "other");
                accounts.put(other, account("o3", "o3"), true);
                modelPresence.refresh(jda);
                verify(presence).setActivity(null);
            }
        }
    }

    @Test void disabledAiDoesNotLookUpModels() throws Exception {
        var jda = mock(JDA.class);
        var presence = mock(Presence.class);
        when(jda.getPresence()).thenReturn(presence);
        try (var modelPresence = new ModelPresence(GUILD, null, null, null)) {
            modelPresence.refresh(jda);
            verify(presence, never()).setActivity(any());
        }
    }
}
