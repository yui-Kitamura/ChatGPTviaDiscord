package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.HttpHeaders;
import java.util.*;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

class LimitCommandListenerTest {
    private static final String GUILD = "123456789012345678";
    private final ChatGptSession session = mock(ChatGptSession.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    private final OpenAiTransport transport = mock(OpenAiTransport.class);
    private final LimitCommandListener listener = new LimitCommandListener(GUILD, session, transport);

    @Test @SuppressWarnings("unchecked") void defersAndRepliesPubliclyWithoutMentions() throws Exception {
        var event = mock(SlashCommandInteractionEvent.class);
        var guild = mock(Guild.class);
        var user = mock(User.class);
        var action = mock(ReplyCallbackAction.class, RETURNS_SELF);
        var hook = mock(InteractionHook.class);
        var edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(event.getName()).thenReturn("gvd");
        when(event.getSubcommandName()).thenReturn("limit");
        when(event.getGuild()).thenReturn(guild);when(event.getUser()).thenReturn(user);
        when(guild.getId()).thenReturn(GUILD);
        when(event.deferReply(false)).thenReturn(action);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        doAnswer(i -> { ((Consumer<InteractionHook>)i.getArgument(0)).accept(hook);return null; }).when(action).queue(any(), any());
        connected();
        try {
            listener.onSlashCommandInteraction(event);
            verify(event).deferReply(false);
            verify(hook, timeout(3000)).editOriginal(contains("接続照会: 成功"));
            verify(edit, timeout(3000)).setAllowedMentions(List.of());
        } finally { listener.onShutdown(null); }
    }

    private ChatGptAccounts.Account connected() throws Exception {
        var account = new ChatGptAccounts.Account();
        account.accessToken = "secret-token"; account.email = "private@example.com";account.model = "chosen";
        when(session.ready(GUILD)).thenReturn(account);
        when(transport.models(account.accessToken)).thenReturn(List.of());
        return account;
    }

    @Test void usesSharedGuildConnectionAndNeverGeneratesAnAnswer() throws Exception {
        var account = connected();
        var snapshot = ApiLimits.from(HttpHeaders.of(Map.of("x-ratelimit-remaining-tokens", List.of("42")), (a,b) -> true)).withOutcome("success");
        when(transport.limits(account.accessToken, account.model)).thenReturn(snapshot);
        String reply = listener.query();
        assertTrue(reply.contains("残り 42"));assertTrue(reply.contains("観測"));assertTrue(reply.contains("プラン全体"));
        assertFalse(reply.contains(account.email));assertFalse(reply.contains(account.accessToken));
        verify(session).ready(GUILD);verify(transport).models(account.accessToken);
        verify(transport, never()).respond(anyString(), anyString(), anyString(), anyList());
    }

    @Test void absentMetadataIsUnknownAndNotZeroOrFull() throws Exception {
        connected();String reply = listener.query();
        assertTrue(reply.contains("観測情報がありません"));assertTrue(reply.contains("https://chatgpt.com/settings/usage"));
        assertFalse(reply.contains("未実装"));assertFalse(reply.contains("残り 0"));assertFalse(reply.contains("100%"));
    }

    @Test void loginAndNetworkFailuresDoNotExposeUpstreamText() throws Exception {
        when(session.ready(GUILD)).thenThrow(new ChatGptException("login_required", 0));
        assertTrue(listener.query().contains("再ログイン"));verifyNoInteractions(transport);
        doThrow(new java.io.IOException("private upstream secret")).when(session).ready(GUILD);
        assertFalse(listener.query().contains("private"));
    }

    @Test void disabledConnectionDoesNotCallTheApi() {
        assertTrue(new LimitCommandListener(GUILD, null, transport).query().contains("接続は無効"));
        verifyNoInteractions(transport);
    }

    @Test void leavesOtherCommandsToTheirHandlers() {
        assertTrue(LimitCommandListener.handles("gvd", "limit"));
        for (String sub : new String[]{"mode", "auth", "skipch", "readch", null}) assertFalse(LimitCommandListener.handles("gvd", sub));
        assertFalse(LimitCommandListener.handles("other", "limit"));
    }

    @Test void otherGuildsDoNotQuerySharedCredentials() {
        var event = mock(SlashCommandInteractionEvent.class);var guild = mock(Guild.class);var user = mock(User.class);
        when(event.getName()).thenReturn("gvd");when(event.getSubcommandName()).thenReturn("limit");when(event.getUser()).thenReturn(user);
        when(event.getGuild()).thenReturn(guild);when(guild.getId()).thenReturn("987654321098765432");
        when(event.reply(anyString())).thenReturn(mock(ReplyCallbackAction.class, RETURNS_SELF));
        listener.onSlashCommandInteraction(event);
        verifyNoInteractions(session, transport);verify(event).reply(contains("設定対象サーバー"));
    }
}
