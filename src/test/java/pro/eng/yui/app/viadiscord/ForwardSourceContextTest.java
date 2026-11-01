package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;

class ForwardSourceContextTest {
    final Guild guild = mock(Guild.class);
    final Member requester = mock(Member.class);
    final SelfMember bot = mock(SelfMember.class);
    final TextChannel channel = mock(TextChannel.class);
    final Message forward = mock(Message.class);
    final MessageReference ref = mock(MessageReference.class);

    ForwardSourceContextTest() {
        when(guild.getId()).thenReturn("guild");
        when(guild.getSelfMember()).thenReturn(bot);
        when(guild.getGuildChannelById("channel")).thenReturn(channel);
        when(forward.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("転送コピー")));
        when(forward.getContentRaw()).thenReturn("");
        when(forward.getMessageReference()).thenReturn(ref);
        when(ref.getGuildId()).thenReturn("guild");
        when(ref.getChannelId()).thenReturn("channel");
        when(ref.getMessageId()).thenReturn("100");
        allow(channel);
    }

    void allow(GuildMessageChannel target) {
        when(requester.hasPermission(target, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
        when(bot.hasPermission(target, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
    }

    static Message post(long id, String body) {
        var message = ChannelHistoryContextTest.post(Long.toString(id), body, false);
        when(message.getIdLong()).thenReturn(id);
        when(message.getChannelId()).thenReturn("channel");
        return message;
    }

    @SuppressWarnings("unchecked")
    void source(Message original, List<Message> before, List<Message> after) {
        source(channel, original, before, after);
    }

    @SuppressWarnings("unchecked")
    void source(GuildMessageChannel channel, Message original, List<Message> before, List<Message> after) {
        RestAction<Message> retrieve = mock(RestAction.class, RETURNS_SELF);
        when(channel.retrieveMessageById("100")).thenReturn(retrieve);
        when(retrieve.complete()).thenReturn(original);
        var beforeAction = mock(MessageHistory.MessageRetrieveAction.class, RETURNS_SELF);
        var afterAction = mock(MessageHistory.MessageRetrieveAction.class, RETURNS_SELF);
        var beforeHistory = mock(MessageHistory.class);
        var afterHistory = mock(MessageHistory.class);
        when(channel.getHistoryBefore("100", 5)).thenReturn(beforeAction);
        when(channel.getHistoryAfter("100", 5)).thenReturn(afterAction);
        when(beforeAction.complete()).thenReturn(beforeHistory);
        when(afterAction.complete()).thenReturn(afterHistory);
        when(beforeHistory.getRetrievedHistory()).thenReturn(before);
        when(afterHistory.getRetrievedHistory()).thenReturn(after);
    }

    @Test void includesBothSidesInChronologicalOrderWithAuthorTimeAndLinks() {
        source(post(100,"元本文"),List.of(post(99,"直前"),post(98,"前の相談")),List.of(post(102,"後の結論"),post(101,"直後")));
        String result = ForwardSourceContext.load(guild, requester, List.of(mock(Message.class),forward));
        assertTrue(result.indexOf("前の相談") < result.indexOf("直前"));
        assertTrue(result.indexOf("直前") < result.indexOf("元本文"));
        assertTrue(result.indexOf("元本文") < result.indexOf("直後"));
        assertTrue(result.indexOf("直後") < result.indexOf("後の結論"));
        assertTrue(result.contains("[元投稿]"));
        assertTrue(result.contains("user_id=user, bot=false"));
        assertTrue(result.contains("2026-10-30 08:00:00 +09:00 JST"));
        assertTrue(result.contains("https://discord.com/channels/guild/channel/101"));
        assertTrue(result.contains("全履歴ではありません"));
    }

    @Test void outsideGuildMissingReferenceAndOrdinaryPostsNeverFetch() {
        when(ref.getGuildId()).thenReturn("elsewhere");
        assertEquals("", ForwardSourceContext.load(guild,requester,List.of(forward)));
        when(forward.getMessageReference()).thenReturn(null);
        assertEquals("", ForwardSourceContext.load(guild,requester,List.of(forward,mock(Message.class))));
        verify(guild,never()).getGuildChannelById(anyString());
        verifyNoInteractions(channel);
    }

    @Test void deniedRequesterOrBotAndMissingChannelNeverFetch() {
        when(requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(false);
        assertTrue(ForwardSourceContext.load(guild,requester,List.of(forward)).contains("未取得"));
        allow(channel);
        when(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(false);
        assertTrue(ForwardSourceContext.load(guild,requester,List.of(forward)).contains("未取得"));
        when(guild.getGuildChannelById("channel")).thenReturn(null);
        assertTrue(ForwardSourceContext.load(guild,requester,List.of(forward)).contains("未取得"));
        verify(channel,never()).retrieveMessageById(anyString());
    }

    @Test void privateThreadRequiresMembershipForRequesterAndBot() {
        var thread = mock(ThreadChannel.class);
        when(guild.getGuildChannelById("channel")).thenReturn(thread);
        allow(thread);
        when(requester.getId()).thenReturn("requester");
        when(bot.getId()).thenReturn("bot");
        var requesterAction = mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class, RETURNS_SELF);
        var botAction = mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class, RETURNS_SELF);
        when(thread.retrieveThreadMemberById("requester")).thenReturn(requesterAction);
        when(thread.retrieveThreadMemberById("bot")).thenReturn(botAction);
        assertTrue(ForwardSourceContext.load(guild,requester,List.of(forward)).contains("未取得"));
        when(requesterAction.complete()).thenReturn(mock(ThreadMember.class));
        assertTrue(ForwardSourceContext.load(guild,requester,List.of(forward)).contains("未取得"));
        verify(thread,never()).retrieveMessageById(anyString());
        when(botAction.complete()).thenReturn(mock(ThreadMember.class));
        source(thread,post(100,"非公開の資料"),List.of(),List.of());
        assertTrue(ForwardSourceContext.load(guild,requester,List.of(forward)).contains("非公開の資料"));
    }

    @Test void deletedSourceAndFetchFailureAreExplicitAndDoNotDiscardForward() {
        source(null,List.of(),List.of());
        String result = ForwardSourceContext.load(guild,requester,List.of(forward));
        assertTrue(result.contains("転送コピーだけ"));
        verify(channel,never()).getHistoryBefore(anyString(),anyInt());
        when(channel.retrieveMessageById("100")).thenThrow(new IllegalStateException("sensitive payload"));
        result = ForwardSourceContext.load(guild,requester,List.of(forward));
        assertTrue(result.contains("未取得"));
        assertFalse(result.contains("sensitive payload"));
        assertTrue(MessageInput.from(forward).text().contains("転送コピー"));
    }

    @Test void trimsBodiesLabelsAttachmentsAndDoesNotExpandNestedForwards() {
        var original = post(100,"x".repeat(2000));
        var neighbor = post(101,"");
        when(neighbor.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("入れ子",mock(Message.Attachment.class))));
        source(original,List.of(),List.of(neighbor));
        String result = ForwardSourceContext.load(guild,requester,List.of(forward));
        assertTrue(result.contains("x".repeat(1000)));
        assertFalse(result.contains("x".repeat(1001)));
        assertTrue(result.contains("本文省略あり"));
        assertTrue(result.contains("添付あり・本文未取得"));
        assertTrue(result.contains("入れ子"));
        verify(neighbor,never()).getMessageReference();
    }
}
