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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"","指定したスレタイ <@123>"})
    @SuppressWarnings("unchecked") void qaResultIsPublicAndReadsBackTitle(String title) throws Exception {
        var event=mock(net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent.class,RETURNS_DEEP_STUBS);
        when(event.getName()).thenReturn("gvd");when(event.getSubcommandName()).thenReturn("mode");
        when(event.isFromGuild()).thenReturn(true);when(event.getGuild().getId()).thenReturn(GUILD);
        var guildChannel=event.getGuildChannel();
        when(event.getMember().hasPermission(guildChannel,net.dv8tion.jda.api.Permission.MANAGE_CHANNEL)).thenReturn(true);
        when(event.getOption("mode").getAsString()).thenReturn("qa");
        if(title.isEmpty())when(event.getOption("title")).thenReturn(null);
        else when(event.getOption("title").getAsString()).thenReturn(title);
        when(event.getChannel().getId()).thenReturn("123456789012345679");
        var hook=mock(net.dv8tion.jda.api.interactions.InteractionHook.class);
        var edit=mock(net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction.class,RETURNS_SELF);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        var defer=event.deferReply(false);
        doAnswer(i->{((java.util.function.Consumer<net.dv8tion.jda.api.interactions.InteractionHook>)i.getArgument(0)).accept(hook);return null;})
                .when(defer).queue(any());
        var settings=new ResponseSettings(directory.resolve("public.properties"));
        var listener=new ModeCommandListener(GUILD,settings,null,null);
        try {
            listener.onSlashCommandInteraction(event);
            verify(event,atLeastOnce()).deferReply(false);
            verify(event,never()).deferReply(true);
            verify(hook,timeout(3000)).editOriginal("この場所の応答モードを qa に保存しました。"+(title.isEmpty()?"":"\n"+title));
            verify(edit,timeout(3000)).setAllowedMentions(java.util.List.of());
        } finally {listener.onShutdown(null);}
    }

    @Test void reportsStoredConnectionConsentAndModelStateWithoutGenerating() throws Exception {
        var transport = mock(OpenAiTransport.class);
        try (var accounts = new ChatGptAccounts(directory.resolve("auth"));
             var session = new ChatGptSession(accounts, transport, 0)) {
            var listener = new ModeCommandListener(GUILD, new ResponseSettings(directory.resolve("response.properties")), session, null);
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
        var disabled = new ModeCommandListener(GUILD, new ResponseSettings(directory.resolve("disabled.properties")), null, null);
        try { assertTrue(disabled.status().contains("接続は無効")); } finally { disabled.onShutdown(null); }
    }
}
