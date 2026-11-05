package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.eng.yui.app.viadiscord.data.local.ResponseSettings;

public class VisitorHistoryContextTest {

    @TempDir
    Path directory;

    public static MessageHistory.MessageRetrieveAction history(Guild guild, Member requester, String channelId,
                                                               String before, Message... posts) {
        var channel = mock(TextChannel.class);
        when(guild.getGuildChannelById(channelId)).thenReturn(channel);
        when(requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
        var bot = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(bot);
        when(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(true);
        var request = mock(MessageHistory.MessageRetrieveAction.class, RETURNS_SELF);
        var history = mock(MessageHistory.class);
        when(channel.getHistoryBefore(before, 100)).thenReturn(request);
        when(request.complete()).thenReturn(history);
        when(history.getRetrievedHistory()).thenReturn(List.of(posts));
        return request;
    }

    @Test
    void rebuildsPlainSpeechBotRepliesAndCurrentRolesAndEditsOnEveryCall() throws Exception {
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        var requester = mock(Member.class);
        var first = ChannelHistoryContextTest.post("1", "メンションなしの相談", false);
        var second = ChannelHistoryContextTest.post("2", "Botの回答", true);
        var current = ChannelHistoryContextTest.post("3", "今回の依頼", false);
        var request = history(guild, requester, "channel", "3", second, first);
        var role = SpeakerIdentityTest.role("223456789012345678", "古いロール");
        var member = SpeakerIdentityTest.member(role);
        when(guild.getMemberById("user")).thenReturn(member);
        var settings = new ResponseSettings(directory.resolve("response.properties"));
        settings.setSpeakerRole("以前の識別名", role.getId());
        String old = VisitorHistoryContext.load(guild, requester, "channel", List.of(current), settings).text();
        assertTrue(old.contains("以前の識別名"));
        assertTrue(old.indexOf("メンションなしの相談") < old.indexOf("Botの回答"));
        assertFalse(old.contains("今回の依頼\n"));
        settings.setSpeakerRole("現在の識別名", role.getId());
        when(first.getContentRaw()).thenReturn("編集後の相談");
        String fresh = VisitorHistoryContext.load(guild, requester, "channel", List.of(current), settings).text();
        assertTrue(fresh.contains("現在の識別名"));
        assertTrue(fresh.contains("編集後の相談"));
        assertFalse(fresh.contains("以前の識別名"));
        assertFalse(fresh.contains("メンションなしの相談"));
        when(member.getRoles()).thenReturn(List.of());
        String removed = VisitorHistoryContext.load(guild, requester, "channel", List.of(current), settings).text();
        assertFalse(removed.contains("現在の識別名"));
        assertTrue(removed.contains("guild:account:user"));
        verify(request, times(3)).complete();
        verify(request, times(3)).timeout(10, TimeUnit.SECONDS);
        when(request.complete().getRetrievedHistory()).thenReturn(List.of(second));
        String deleted = VisitorHistoryContext.load(guild, requester, "channel", List.of(current), settings).text();
        assertFalse(deleted.contains("編集後の相談"));
    }

    @Test
    void resolvesMentionedUsersAndRenamedOrDeletedRolesFromCurrentState() throws Exception {
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        var requester = mock(Member.class);
        String userId = "323456789012345678", roleId = "423456789012345678";
        var post = ChannelHistoryContextTest.post("1", "<@"+ userId +"> <@&"+ roleId +">", false);
        var current = ChannelHistoryContextTest.post("2", "current", false);
        history(guild, requester, "channel", "2", post);
        var role = SpeakerIdentityTest.role(roleId, "旧ロール名");
        when(guild.getRoleById(roleId)).thenReturn(role);
        var mentionedMember = SpeakerIdentityTest.member(role);
        when(guild.getMemberById(userId)).thenReturn(mentionedMember);
        var settings = new ResponseSettings(directory.resolve("response.properties"));
        String before = VisitorHistoryContext.load(guild, requester, "channel", List.of(current), settings).text();
        assertTrue(before.contains("旧ロール名"));
        assertTrue(before.contains("guild:role:"+ roleId));
        when(role.getName()).thenReturn("新ロール名");
        String after = VisitorHistoryContext.load(guild, requester, "channel", List.of(current), settings).text();
        assertTrue(after.contains("新ロール名"));
        assertFalse(after.contains("旧ロール名"));
        when(guild.getRoleById(roleId)).thenReturn(null);
        when(guild.getMemberById(userId)).thenReturn(null);
        String deleted = VisitorHistoryContext.load(guild, requester, "channel", List.of(current), settings).text();
        assertFalse(deleted.contains("新ロール名"));
        assertTrue(deleted.contains("\""+ roleId +"\":\"unavailable\""));
        assertTrue(deleted.contains("guild:account:"+ userId));
    }

    @Test
    void boundsRecordsExcludesCurrentAndMarksAttachmentsAndMissingMembers() throws Exception {
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        var requester = mock(Member.class);
        var current = ChannelHistoryContextTest.post("3", "current", false);
        var attachment = ChannelHistoryContextTest.post("2", "", false);
        when(attachment.getAttachments()).thenReturn(List.of(mock(Message.Attachment.class)));
        history(guild, requester, "channel", "3", current, attachment,
                ChannelHistoryContextTest.post("1", "x".repeat(32_000), false));
        String text = VisitorHistoryContext.load(
            guild, requester, "channel", List.of(current), new ResponseSettings(directory.resolve("response.properties"))
        ).text();
        assertTrue(text.contains("取得投稿数: 1"));
        assertTrue(text.contains("省略あり"));
        assertTrue(text.contains("添付あり・本文未取得"));
        assertTrue(text.contains("\"memberStatus\":\"unavailable\""));
        assertFalse(text.contains("current"));
        assertFalse(text.contains("x".repeat(100)));
    }

    @Test
    void permissionFailureIsExplicitAndDoesNotReadHistory() throws Exception {
        var guild = mock(Guild.class);
        var requester = mock(Member.class);
        var current = ChannelHistoryContextTest.post("3", "current", false);
        var request = history(guild, requester, "channel", "3");
        var channel = guild.getGuildChannelById("channel");
        when(requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)).thenReturn(false);
        assertThrows(ChannelHistoryContext.Unavailable.class, () -> VisitorHistoryContext.load(
            guild, requester, "channel", List.of(current), new ResponseSettings(directory.resolve("response.properties"))
        ));
        verify(request, never()).complete();
    }
}
