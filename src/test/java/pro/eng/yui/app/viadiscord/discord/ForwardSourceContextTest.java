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
        String id = original == null ? "100" : original.getId();
        RestAction<Message> retrieve = mock(RestAction.class, RETURNS_SELF);
        when(channel.retrieveMessageById(id)).thenReturn(retrieve);
        when(retrieve.complete()).thenReturn(original);
        var beforeAction = page(before);
        var afterAction = page(after);
        when(channel.getHistoryBefore(eq(id), anyInt())).thenReturn(beforeAction);
        when(channel.getHistoryAfter(eq(id), anyInt())).thenReturn(afterAction);
    }

    private static MessageHistory.MessageRetrieveAction page(List<Message> posts) {
        var action = mock(MessageHistory.MessageRetrieveAction.class, RETURNS_SELF);
        var history = mock(MessageHistory.class);
        when(action.complete()).thenReturn(history);
        when(history.getRetrievedHistory()).thenReturn(posts);
        return action;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = "0")
    void omittedSourceGuildStillExpandsOnlyChannelsInCurrentGuild(String sourceGuild) {
        when(ref.getGuildId()).thenReturn(sourceGuild);
        source(post(100, "source with omitted guild"), List.of(post(99, "before")), List.of(post(101, "after")));
        String result = ForwardSourceContext.load(guild, requester, List.of(mock(Message.class), forward));
        assertTrue(result.contains("source with omitted guild"));
        assertTrue(result.contains("before"));
        assertTrue(result.contains("after"));

        when(guild.getGuildChannelById("channel")).thenReturn(null);
        clearInvocations(channel);
        assertTrue(ForwardSourceContext.load(guild, requester, List.of(forward)).contains("未取得"));
        verify(channel, never()).retrieveMessageById(anyString());
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
        verify(channel).getHistoryBefore("100", 100);
        verify(channel).getHistoryAfter("100", 100);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void appsDoNotConsumeUserQuotaAndBothSidesContinueAcrossPages(boolean reply) {
        if (reply) {
            when(forward.getType()).thenReturn(MessageType.INLINE_REPLY);
            when(forward.getChannelId()).thenReturn("channel");
        }
        var before = new ArrayList<Message>();
        var after = new ArrayList<Message>();
        for(int offset=1; offset<=100; offset++) {
            var left = post(1_100 - offset, "left-app-" + offset);
            var right = post(1_100 + offset, "right-app-" + offset);
            if (offset % 3 == 0) {
                when(left.getAuthor().isBot()).thenReturn(true);
                when(right.getAuthor().isBot()).thenReturn(true);
            } else if (offset % 3 == 1) {
                when(left.isWebhookMessage()).thenReturn(true);
                when(right.isWebhookMessage()).thenReturn(true);
            } else {
                when(left.getApplicationIdLong()).thenReturn(123L);
                when(right.getApplicationIdLong()).thenReturn(123L);
            }
            before.add(left);
            after.add(right);
        }
        Collections.reverse(before);
        Collections.reverse(after);
        source(post(1_100, "center"), before, after);
        when(ref.getMessageId()).thenReturn("1100");
        var earlier = new ArrayList<Message>();
        var later = new ArrayList<Message>();
        for(int offset=1; offset<=21; offset++) {
            earlier.add(post(1_000 - offset, "earlier-user-" + offset));
            later.add(post(1_200 + offset, "later-user-" + offset));
        }
        var nextBefore = page(earlier);
        var nextAfter = page(later);
        when(channel.getHistoryBefore("1000", 100)).thenReturn(nextBefore);
        when(channel.getHistoryAfter("1200", 100)).thenReturn(nextAfter);
        String result = reply ? DiscordReplyContext.surrounding(guild, requester, forward) :
            ForwardSourceContext.load(guild, requester, List.of(forward));
        assertTrue(result.contains("earlier-user-20\n"));
        assertTrue(result.contains("later-user-20\n"));
        assertFalse(result.contains("earlier-user-21\n"));
        assertFalse(result.contains("later-user-21\n"));
        assertTrue(result.contains("left-app-1\n"));
        assertTrue(result.contains("right-app-1\n"));
        assertTrue(result.contains("app_origin=true"));
        String target = reply ? "[返信対象]" : "[元投稿]";
        assertTrue(result.indexOf("earlier-user-20") < result.indexOf(target));
        assertTrue(result.indexOf("later-user-20") > result.indexOf(target));
        assertTrue(result.contains("前側：取得 ユーザー=20件・アプリ=100件／この入力の本文 ユーザー=20件・アプリ=100件"));
        assertTrue(result.contains("後側：取得 ユーザー=20件・アプリ=100件／この入力の本文 ユーザー=20件・アプリ=100件"));
    }

    @Test
    void textBudgetPreservesCountedUsersBeforeAppExcerpts() {
        when(ref.getMessageId()).thenReturn("1100");
        var before = new ArrayList<Message>();
        for(int offset=1; offset<=70; offset++) {
            var message = post(1_100 - offset, (offset <= 50 ? "app-" : "user-") + offset + "x".repeat(2_000));
            when(message.getAuthor().isBot()).thenReturn(offset <= 50);
            before.add(message);
        }
        source(post(1_100, "original"), before, List.of());
        String result = ForwardSourceContext.load(guild, requester, List.of(forward));
        assertTrue(result.length() <= 100_000);
        assertTrue(result.contains("user-70x"));
        assertTrue(result.contains("app-1x"));
        assertTrue(result.contains("[元投稿]"));
        assertTrue(result.contains("全体上限による省略あり"));
        assertTrue(result.contains("取得 ユーザー=20件・アプリ=50件／この入力の本文 ユーザー=20件"));
    }

    @Test
    void appFloodHasBoundedScanningAndCannotTruncateAwayOriginal() {
        when(ref.getMessageId()).thenReturn("1100");
        var apps = new ArrayList<Message>();
        for(int offset=1; offset<=1_000; offset++) {
            var app = post(1_100 - offset, "app-" + offset + "x".repeat(2_000));
            when(app.getAuthor().isBot()).thenReturn(true);
            apps.add(app);
        }
        source(post(1_100, "important original"), List.of(), List.of(post(1_101, "nearby user")));
        when(channel.getHistoryBefore(anyString(), eq(100))).thenAnswer(call -> {
            int start = 1_100 - Integer.parseInt(call.getArgument(0));
            return page(apps.subList(start, Math.min(start + 100, apps.size())));
        });
        String result = ForwardSourceContext.load(guild, requester, List.of(forward));
        assertTrue(result.length() <= 100_000);
        assertTrue(result.contains("[元投稿]"));
        assertTrue(result.contains("important original"));
        assertTrue(result.contains("nearby user"));
        assertTrue(result.contains("app-1x"));
        assertTrue(result.contains("取得件数上限による省略あり"));
        assertTrue(result.contains("全体上限による省略あり"));
        verify(channel, times(10)).getHistoryBefore(anyString(), eq(100));
        assertTrue(result.endsWith("--- 転送元コンテキスト終わり ---\n"));
    }

    @Test
    void replyAppOnlyHistoryEndsWithoutCountingAppsAsUsers() {
        when(forward.getType()).thenReturn(MessageType.INLINE_REPLY);
        when(forward.getChannelId()).thenReturn("channel");
        var apps = new ArrayList<Message>();
        for(int offset=1; offset<=20; offset++) {
            var app = post(100 - offset, "reply-app-" + offset);
            when(app.getAuthor().isBot()).thenReturn(true);
            apps.add(app);
        }
        source(post(100, "reply target"), apps, List.of());
        String result = DiscordReplyContext.surrounding(guild, requester, forward);
        assertTrue(result.contains("reply-app-20\n"));
        verify(channel).getHistoryBefore("100", 100);
        assertTrue(result.contains("前側：取得 ユーザー=0件・アプリ=20件"));
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
