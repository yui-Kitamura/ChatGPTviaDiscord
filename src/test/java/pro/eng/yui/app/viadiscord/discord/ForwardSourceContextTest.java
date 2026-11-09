package pro.eng.yui.app.viadiscord.discord;

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

public class ForwardSourceContextTest {

    final Guild guild = mock(Guild.class);
    final Member requester = mock(Member.class);
    public final SelfMember bot = mock(SelfMember.class);
    public final TextChannel channel = mock(TextChannel.class);
    final Message forward = mock(Message.class);
    public final MessageReference ref = mock(MessageReference.class);

    public ForwardSourceContextTest() {
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

    public static Message post(long id, String body) {
        var message = ChannelHistoryContextTest.post(Long.toString(id), body, false);
        when(message.getIdLong()).thenReturn(id);
        when(message.getChannelId()).thenReturn("channel");
        return message;
    }

    @SuppressWarnings("unchecked")
    public void source(Message original, List<Message> before, List<Message> after) {
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
        when(channel.getHistoryBefore("100", 20)).thenReturn(beforeAction);
        when(channel.getHistoryAfter("100", 20)).thenReturn(afterAction);
        when(beforeAction.complete()).thenReturn(beforeHistory);
        when(afterAction.complete()).thenReturn(afterHistory);
        when(beforeHistory.getRetrievedHistory()).thenReturn(before);
        when(afterHistory.getRetrievedHistory()).thenReturn(after);
    }

    @Test
    void includesBothSidesInChronologicalOrderWithAuthorTimeAndLinks() {
        when(channel.getName()).thenReturn("相談");
        when(channel.getId()).thenReturn("channel");
        var category = mock(net.dv8tion.jda.api.entities.channel.concrete.Category.class);
        when(category.getName()).thenReturn("開発");
        when(channel.getParentCategory()).thenReturn(category);
        source(
            post(100, "元本文"),
            List.of(post(99, "直前"), post(98, "前の相談")),
            List.of(post(102, "後の結論"), post(101, "直後"))
        );
        String result = ForwardSourceContext.load(guild, requester, List.of(mock(Message.class), forward));
        assertTrue(result.indexOf("前の相談") < result.indexOf("直前"));
        assertTrue(result.indexOf("直前") < result.indexOf("元本文"));
        assertTrue(result.indexOf("元本文") < result.indexOf("直後"));
        assertTrue(result.indexOf("直後") < result.indexOf("後の結論"));
        assertTrue(result.contains("[元投稿]"));
        assertTrue(result.contains("user_id=user, bot=false"));
        assertTrue(result.contains("2026-10-30 08:00:00 +09:00 JST"));
        assertTrue(result.contains("https://discord.com/channels/guild/channel/101"));
        assertTrue(result.contains("全履歴ではありません"));
        assertTrue(result.contains("--- #開発：相談 (channel_id=channel) ---"));
    }

    @Test
    void outsideGuildMissingReferenceAndOrdinaryPostsNeverFetch() {
        when(ref.getGuildId()).thenReturn("elsewhere");
        assertEquals("", ForwardSourceContext.load(guild, requester, List.of(forward)));
        when(forward.getMessageReference()).thenReturn(null);
        assertEquals("", ForwardSourceContext.load(guild, requester, List.of(forward, mock(Message.class))));
        verify(guild, never()).getGuildChannelById(anyString());
        verifyNoInteractions(channel);
    }

    @Test
    void deniedRequesterOrBotAndMissingChannelNeverFetch() {
        when(requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(
            false
        );
        assertTrue(ForwardSourceContext.load(guild, requester, List.of(forward)).contains("未取得"));
        allow(channel);
        when(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(false);

        assertTrue(ForwardSourceContext.load(guild, requester, List.of(forward)).contains("未取得"));
        when(guild.getGuildChannelById("channel")).thenReturn(null);

        assertTrue(ForwardSourceContext.load(guild, requester, List.of(forward)).contains("未取得"));
        verify(channel, never()).retrieveMessageById(anyString());
    }

    @Test
    void privateThreadRequiresMembershipForRequesterAndBot() {
        var thread = mock(ThreadChannel.class);
        when(guild.getGuildChannelById("channel")).thenReturn(thread);
        allow(thread);
        when(requester.getId()).thenReturn("requester");
        when(bot.getId()).thenReturn("bot");
        var requesterAction = mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class, RETURNS_SELF);
        var botAction = mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class, RETURNS_SELF);
        when(thread.retrieveThreadMemberById("requester")).thenReturn(requesterAction);
        when(thread.retrieveThreadMemberById("bot")).thenReturn(botAction);
        assertTrue(ForwardSourceContext.load(guild, requester, List.of(forward)).contains("未取得"));
        when(requesterAction.complete()).thenReturn(mock(ThreadMember.class));

        assertTrue(ForwardSourceContext.load(guild, requester, List.of(forward)).contains("未取得"));
        verify(thread, never()).retrieveMessageById(anyString());
        when(botAction.complete()).thenReturn(mock(ThreadMember.class));
        when(thread.getParentChannel()).thenReturn(mock(net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion.class));
        source(thread, post(100, "非公開の資料"), List.of(), List.of());
        assertTrue(ForwardSourceContext.load(guild, requester, List.of(forward)).contains("非公開の資料"));
    }

    @Test
    void deletedSourceAndFetchFailureAreExplicitAndDoNotDiscardForward() {
        source(null, List.of(), List.of());
        String result = ForwardSourceContext.load(guild, requester, List.of(forward));
        assertTrue(result.contains("取得済みの対象本文だけ"));
        verify(channel, never()).getHistoryBefore(anyString(), anyInt());
        when(channel.retrieveMessageById("100")).thenThrow(new IllegalStateException("sensitive payload"));
        result = ForwardSourceContext.load(guild, requester, List.of(forward));
        assertTrue(result.contains("未取得"));
        assertFalse(result.contains("sensitive payload"));
        assertTrue(MessageInput.from(forward).text().contains("転送コピー"));
    }

    @Test
    void expandedWindowIncludesTwentyMessagesOnBothSides() {
        var before = new ArrayList<Message>();
        var after = new ArrayList<Message>();
        for(int offset=1; offset<=20; offset++) {
            before.add(post(100 - offset, "before-" + offset));
            after.add(post(100 + offset, "after-" + offset));
        }
        source(post(100, "center"), before, after);
        String result = ForwardSourceContext.load(guild, requester, List.of(forward));
        assertTrue(result.indexOf("before-20") < result.indexOf("center"));
        assertTrue(result.indexOf("after-20") > result.indexOf("center"));
        assertEquals(40, result.split("\\[前後の投稿\\]", -1).length - 1);
        verify(channel).getHistoryBefore("100", 20);
        verify(channel).getHistoryAfter("100", 20);
    }

    @Test
    void replyIncludesNeighborhoodWithoutRewritingQuotedText() {
        when(forward.getType()).thenReturn(MessageType.INLINE_REPLY);
        when(forward.getChannelId()).thenReturn("channel");
        source(post(100, "転送元と元投稿について"), List.of(post(99, "before")), List.of(post(101, "after")));
        String result = DiscordReplyContext.surrounding(guild, requester, forward);
        assertTrue(result.contains("返信先の前後コンテキスト"));
        assertTrue(result.contains("[返信対象]"));
        assertTrue(result.contains("転送元と元投稿について"));
        assertTrue(result.indexOf("before") < result.indexOf("転送元と元投稿について"));
        assertTrue(result.indexOf("after") > result.indexOf("転送元と元投稿について"));
    }

    @Test
    void replyDoesNotFetchOrdinaryOrCrossChannelReferences() {
        assertEquals("", DiscordReplyContext.surrounding(guild, requester, forward));
        when(forward.getType()).thenReturn(MessageType.INLINE_REPLY);
        when(forward.getChannelId()).thenReturn("other");
        assertEquals("", DiscordReplyContext.surrounding(guild, requester, forward));
        verifyNoInteractions(channel);
    }

    @Test
    void replyPermissionDenialAndFetchFailureAreExplicit() {
        when(forward.getType()).thenReturn(MessageType.INLINE_REPLY);
        when(forward.getChannelId()).thenReturn("channel");
        when(requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(false);
        assertTrue(DiscordReplyContext.surrounding(guild, requester, forward).contains("返信先の前後コンテキスト未取得"));
        verify(channel, never()).retrieveMessageById(anyString());
        allow(channel);
        when(channel.retrieveMessageById("100")).thenThrow(new IllegalStateException("private details"));
        String result = DiscordReplyContext.surrounding(guild, requester, forward);
        assertTrue(result.contains("未取得"));
        assertFalse(result.contains("private details"));
    }

    @Test
    void trimsBodiesLabelsAttachmentsAndDoesNotExpandNestedForwards() {
        var original = post(100, "x".repeat(3000));
        var neighbor = post(101, "");
        when(neighbor.getMessageSnapshots()).thenReturn(
            List.of(MessageInputTest.snapshot("入れ子", mock(Message.Attachment.class)))
        );
        source(original, List.of(), List.of(neighbor));
        String result = ForwardSourceContext.load(guild, requester, List.of(forward));
        assertTrue(result.contains("x".repeat(2000)));
        assertFalse(result.contains("x".repeat(2001)));
        assertTrue(result.contains("本文省略あり"));
        assertTrue(result.contains("添付あり・本文未取得"));
        assertTrue(result.contains("入れ子"));
        verify(neighbor, never()).getMessageReference();
    }
}
