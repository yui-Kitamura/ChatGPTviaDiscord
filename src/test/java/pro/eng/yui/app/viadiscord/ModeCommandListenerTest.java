package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModeCommandListenerTest {
    @TempDir Path directory;
    private static final String GUILD = "123456789012345678";

    @Test void reportsStoredConnectionConsentAndModelStateWithoutGenerating() throws Exception {
        var transport = mock(OpenAiTransport.class);
        try (var accounts = new ChatGptAccounts(directory.resolve("auth"));
             var session = new ChatGptSession(accounts, transport, 0)) {
            var listener = new ModeCommandListener(GUILD, new ResponseSettings(directory.resolve("response.properties")), session);
            try {
                assertTrue(listener.status().contains("ログインが必要"));
                var account = new ChatGptAccounts.Account();account.accessToken = "private-token";account.email = "private@example.com";
                String key = ChatGptAccounts.key(GUILD, "default");accounts.put(key, account, true);
                assertTrue(listener.status().contains("同意が必要"));
                account.scope = "chatgpt.tokens.use.direct";accounts.put(key, account, true);
                assertTrue(listener.status().contains("モデルが未選択"));
                account.model = "chosen";account.expiresAt = Instant.now().plusSeconds(3600).getEpochSecond();accounts.put(key, account, true);
                assertTrue(listener.status().contains("接続を設定済み"));assertFalse(listener.status().contains("未接続"));
                assertFalse(listener.status().contains("private"));
                accounts.clearTokens(key);assertTrue(listener.status().contains("ログインが必要"));
                verifyNoInteractions(transport);
            } finally { listener.onShutdown(null); }
        }
        var disabled = new ModeCommandListener(GUILD, new ResponseSettings(directory.resolve("disabled.properties")), null);
        try { assertTrue(disabled.status().contains("接続は無効")); } finally { disabled.onShutdown(null); }
    }
}
