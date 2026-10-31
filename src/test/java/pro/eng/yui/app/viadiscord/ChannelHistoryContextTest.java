package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.*;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;

class ChannelHistoryContextTest {
    static final String ID = "123456789012345678";
    final Guild guild = mock(Guild.class);
    final Member requester = mock(Member.class);
    final SelfMember bot = mock(SelfMember.class);
    final TextChannel channel = mock(TextChannel.class);
    final MessageHistory history = mock(MessageHistory.class);

    @SuppressWarnings("unchecked")
    RestAction<List<Message>> setup(Message... messages) {
        when(guild.getId()).thenReturn("guild");
        when(guild.getSelfMember()).thenReturn(bot);
        when(guild.getGuildChannelById(ID)).thenReturn(channel);
        when(channel.getName()).thenReturn("臼井阿原");
        when(channel.getHistory()).thenReturn(history);
        when(requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
        when(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
        RestAction<List<Message>> request = mock(RestAction.class);
        when(history.retrievePast(100)).thenReturn(request);
        when(request.complete()).thenReturn(List.of(messages));
        return request;
    }

    static Message post(String id, String text, boolean isBot) {
        var message = ServerModelContextTest.message(text);
        var author = mock(User.class);
        when(author.getName()).thenReturn(isBot ? "GvD" : "利用者");
        when(author.getId()).thenReturn(isBot ? "bot" : "user");
        when(author.isBot()).thenReturn(isBot);
        when(message.getAuthor()).thenReturn(author);
        when(message.getId()).thenReturn(id);
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-10-30T08:00:00+09:00"));
        when(message.getAttachments()).thenReturn(List.of());
        return message;
    }

    @Test void readsMentionOnceAndIncludesChronologicalHumanAndBotPostsWithSources() throws Exception {
        var request = setup(post("2", "決まったこと", true), post("1", "相談内容", false));
        String result = ChannelHistoryContext.load(guild, requester, "<#"+ID+"> の内容readback <#"+ID+">");
        assertTrue(result.indexOf("相談内容") < result.indexOf("決まったこと"));
        assertTrue(result.contains("user_id=user, bot=false"));
        assertTrue(result.contains("user_id=bot, bot=true"));
        assertTrue(result.contains("2026-10-30T08:00+09:00"));
        assertTrue(result.contains("https://discord.com/channels/guild/"+ID+"/1"));
        assertTrue(result.contains("取得投稿数: 2"));
        verify(request, times(1)).complete();
    }

    @Test void forwardedTextAndAttachmentOnlyPostsAreIncluded() throws Exception {
        var text = post("2", "", false);
        when(text.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("転送された相談")));
        var attachment = post("1", "", false);
        when(attachment.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("", mock(Message.Attachment.class))));
        setup(text, attachment);
        String result = ChannelHistoryContext.load(guild, requester, "<#"+ID+">");
        assertTrue(result.contains("転送された相談"));
        assertTrue(result.contains("[添付あり・本文未取得]"));
        assertTrue(result.contains("取得投稿数: 2"));
    }

    @Test void forwardedTextCountsTowardHistoryBudget() throws Exception {
        var newest = post("2", "", false);
        var older = post("1", "", false);
        when(newest.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("最新"+"x".repeat(80000))));
        when(older.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("省略対象"+"x".repeat(80000))));
        setup(newest, older);
        String result = ChannelHistoryContext.load(guild, requester, "<#"+ID+">");
        assertTrue(result.contains("最新"));
        assertFalse(result.contains("省略対象"));
        assertTrue(result.contains("省略あり"));
    }

    @Test void noMentionDoesNotReadDiscord() throws Exception {
        assertEquals("", ChannelHistoryContext.load(guild, requester, "こんにちは"));
        verifyNoInteractions(guild);
    }

    @Test void missingChannelAndDeniedRequesterOrBotNeverReadHistory() {
        setup(post("1", "private", false));
        when(requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(false);
        assertThrows(ChannelHistoryContext.Unavailable.class, () -> ChannelHistoryContext.load(guild, requester, "<#"+ID+">"));
        verify(channel, never()).getHistory();
        when(requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
        when(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(false);
        assertThrows(ChannelHistoryContext.Unavailable.class, () -> ChannelHistoryContext.load(guild, requester, "<#"+ID+">"));
        verify(channel, never()).getHistory();
        when(guild.getGuildChannelById(ID)).thenReturn(null);
        assertThrows(ChannelHistoryContext.Unavailable.class, () -> ChannelHistoryContext.load(guild, requester, "<#"+ID+">"));
    }

    @Test void paginatesAndStopsAtFiveThousandWithExplicitOmission() throws Exception {
        setup(Collections.nCopies(100, post("2", "newer", false)).toArray(Message[]::new));
        var request = mock(MessageHistory.MessageRetrieveAction.class);
        var older = mock(MessageHistory.class);
        when(channel.getHistoryBefore("2", 100)).thenReturn(request);
        when(request.complete()).thenReturn(older);
        var oldest=post("1", "oldest", false);
        when(older.getRetrievedHistory()).thenReturn(List.of(oldest));
        String result = ChannelHistoryContext.load(guild, requester, "<#"+ID+">");
        assertTrue(result.indexOf("oldest") < result.indexOf("newer"));
        var newer=post("2", "newer", false);
        when(older.getRetrievedHistory()).thenReturn(Collections.nCopies(100, newer));
        clearInvocations(request);
        result = ChannelHistoryContext.load(guild, requester, "<#"+ID+">");
        assertTrue(result.contains("省略あり"));
        assertTrue(result.contains("全履歴ではありません"));
        verify(request,times(50)).complete();
    }

    @Test void readsPastFiveHundredPostsAndTheOldCharacterLimit() throws Exception {
        var newest=post("2", "newer", false);
        var page=Collections.nCopies(100, newest);
        setup(page.toArray(Message[]::new));
        var request=mock(MessageHistory.MessageRetrieveAction.class);
        var older=mock(MessageHistory.class);
        when(channel.getHistoryBefore("2",100)).thenReturn(request);
        when(request.complete()).thenReturn(older);
        var oldest=post("1","oldest beyond five hundred",false);
        when(older.getRetrievedHistory()).thenReturn(page,page,page,page,page,List.of(oldest));
        String result=ChannelHistoryContext.load(guild,requester,"<#"+ID+">");
        assertTrue(result.contains("取得投稿数: 601"));
        assertTrue(result.length()>32_000);
        assertTrue(result.contains("oldest beyond five hundred"));
        assertTrue(result.indexOf("oldest beyond five hundred")<result.indexOf("newer"));
        assertFalse(result.contains("省略あり"));
    }

    @Test void threeChannelsShareTheTotalCharacterBudget() throws Exception {
        // Reuse one source fixture under three IDs to exercise aggregate allocation.
        setup(post("2","newest"+"x".repeat(50000),false),post("1","omitted"+"x".repeat(50000),false));
        when(guild.getGuildChannelById("123456789012345679")).thenReturn(channel);
        when(guild.getGuildChannelById("123456789012345680")).thenReturn(channel);
        String result=ChannelHistoryContext.load(guild,requester,
                "<#"+ID+"> <#123456789012345679> <#123456789012345680>");
        assertEquals(3,result.split("取得投稿数: 1",-1).length-1);
        assertFalse(result.contains("omitted"));
        assertTrue(result.length()<256_000);
    }

    @Test void characterBudgetKeepsNewestPostsAndReportsOmission() throws Exception {
        setup(post("2", "newest" + "x".repeat(80000), false), post("1", "omitted" + "x".repeat(80000), false));
        String result = ChannelHistoryContext.load(guild, requester, "<#"+ID+">");
        assertTrue(result.contains("newest"));
        assertFalse(result.contains("omitted"));
        assertTrue(result.contains("省略あり"));
    }

    @Test void distinguishesEmptyHistoryFromUnreadableBodiesAndSanitizesApiErrors() throws Exception {
        var request = setup();
        assertTrue(ChannelHistoryContext.load(guild, requester, "<#"+ID+">").contains("取得投稿数: 0"));
        var blank=post("1", "", false);
        when(request.complete()).thenReturn(List.of(blank));
        assertThrows(ChannelHistoryContext.Unavailable.class, () -> ChannelHistoryContext.load(guild, requester, "<#"+ID+">"));
        when(request.complete()).thenThrow(new IllegalStateException("secret upstream body"));
        var error = assertThrows(ChannelHistoryContext.Unavailable.class, () -> ChannelHistoryContext.load(guild, requester, "<#"+ID+">"));
        assertNotNull(error.getCause());
        assertEquals(FailureDiagnostics.Reason.FETCH_FAILED,error.details().reason());
        assertFalse(FailureDiagnosticsTest.capture(()->FailureDiagnostics.log("CHANNEL_HISTORY",error)).contains("secret"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test void rejectsTooManyChannelsBeforeAnyFetch() {
        assertThrows(ChannelHistoryContext.Unavailable.class, () -> ChannelHistoryContext.load(guild, requester,
                "<#123456789012345671> <#123456789012345672> <#123456789012345673> <#123456789012345674>"));
        verifyNoInteractions(guild);
    }

    @Test void privateThreadRequiresMembershipBeforeReading() {
        setup();
        var thread = mock(ThreadChannel.class);
        when(guild.getGuildChannelById(ID)).thenReturn(thread);
        when(requester.hasPermission(thread, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
        when(bot.hasPermission(thread, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
        when(requester.getId()).thenReturn("user");
        var memberRequest = mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class);
        when(thread.retrieveThreadMemberById("user")).thenReturn(memberRequest);
        when(memberRequest.complete()).thenThrow(new IllegalStateException("not a member"));
        assertThrows(ChannelHistoryContext.Unavailable.class, () -> ChannelHistoryContext.load(guild, requester, "<#"+ID+">"));
        verify(thread, never()).getHistory();
    }
}
