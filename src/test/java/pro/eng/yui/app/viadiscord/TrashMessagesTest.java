package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.pagination.ReactionPaginationAction;

class TrashMessagesTest {
    @TempDir Path directory;

    @Test void forwardMustSucceedBeforeDelete() {
        Message message = mock(Message.class);
        ThreadChannel target = mock(ThreadChannel.class);
        MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
        @SuppressWarnings("unchecked") AuditableRestAction<Void> delete = mock(AuditableRestAction.class);
        when(message.getId()).thenReturn("123");
        when(message.forwardTo(target)).thenReturn(send);
        when(message.delete()).thenReturn(delete);
        Set<String> sent = new HashSet<>();
        when(send.complete()).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> TrashMessages.move(message, target, sent));
        verify(message, never()).delete();
        assertTrue(sent.isEmpty());
        doReturn(mock(Message.class)).when(send).complete();
        clearInvocations(send);
        TrashMessages.move(message, target, sent);
        var order = inOrder(send, delete);
        order.verify(send).complete(); order.verify(delete).complete();
        assertEquals(Set.of("123"), sent);
    }

    @Test void failedDeletionDoesNotForwardAgain() {
        Message message = mock(Message.class);
        ThreadChannel target = mock(ThreadChannel.class);
        MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
        @SuppressWarnings("unchecked") AuditableRestAction<Void> delete = mock(AuditableRestAction.class);
        when(message.getId()).thenReturn("123");
        when(message.forwardTo(target)).thenReturn(send);
        when(message.delete()).thenReturn(delete);
        when(delete.complete()).thenThrow(new IllegalStateException()).thenReturn(null);
        Set<String> sent = new HashSet<>();
        assertThrows(IllegalStateException.class, () -> TrashMessages.move(message, target, sent));
        TrashMessages.move(message, target, sent);
        verify(message, times(1)).forwardTo(target);
        verify(delete, times(2)).complete();
    }

    @Test void recoveredForwardOnlyRetriesDeletion() {
        Message message = mock(Message.class);
        when(message.getId()).thenReturn("123");
        @SuppressWarnings("unchecked") AuditableRestAction<Void> delete = mock(AuditableRestAction.class);
        when(message.delete()).thenReturn(delete);
        TrashMessages.move(message, mock(ThreadChannel.class), new HashSet<>(Set.of("123")));
        verify(message, never()).forwardTo(any());
        verify(delete).complete();
    }

    @Test void onlyCurrentAllowedHumanReactionApproves() throws Exception {
        AiAccess access = new AiAccess(directory, AiAccessTest.ROLE);
        Guild guild = mock(Guild.class);
        Message message = mock(Message.class);
        when(message.getType()).thenReturn(MessageType.DEFAULT);
        MessageReaction reaction = mock(MessageReaction.class);
        when(reaction.getEmoji()).thenReturn((net.dv8tion.jda.api.entities.emoji.EmojiUnion) Emoji.fromUnicode("✅"));
        when(message.getReactions()).thenReturn(List.of(reaction));
        ReactionPaginationAction users = mock(ReactionPaginationAction.class);
        when(message.retrieveReactionUsers(eq(Emoji.fromUnicode("✅")), any(MessageReaction.ReactionType.class))).thenReturn(users);
        User bot = mock(User.class); when(bot.isBot()).thenReturn(true);
        User human = mock(User.class); when(human.getId()).thenReturn("user");
        when(users.iterator()).thenAnswer(ignored -> {
            var iterator = List.of(bot, human).iterator();
            @SuppressWarnings("unchecked")
            var cursor = (net.dv8tion.jda.api.requests.restaction.pagination.PaginationAction.PaginationIterator<User>)
                    mock(net.dv8tion.jda.api.requests.restaction.pagination.PaginationAction.PaginationIterator.class);
            when(cursor.hasNext()).thenAnswer(call -> iterator.hasNext());
            when(cursor.next()).thenAnswer(call -> iterator.next());
            return cursor;
        });
        @SuppressWarnings("unchecked") CacheRestAction<Member> member = mock(CacheRestAction.class, RETURNS_SELF);
        when(guild.retrieveMemberById("user")).thenReturn(member);
        Member allowed = AiAccessTest.member(false, AiAccessTest.ROLE);
        when(member.complete()).thenReturn(allowed);
        try (var trash = new TrashMessages("guild", access)) {
            assertTrue(trash.approved(guild, message));
            verify(member).useCache(false);
            access.update(AiAccessTest.ROLE, false);
            assertFalse(trash.approved(guild, message));
            when(message.getReactions()).thenReturn(List.of());
            assertFalse(trash.approved(guild, message));
        }
    }
    @Test void missingOrAmbiguousSourceDoesNotMoveAnything() throws Exception {
        var jda = mock(net.dv8tion.jda.api.JDA.class);
        var guild = mock(Guild.class);
        when(jda.getGuildById("guild")).thenReturn(guild);
        try (var trash = new TrashMessages("guild", new AiAccess(directory, ""))) {
            when(guild.getTextChannelsByName("チラシの裏", false)).thenReturn(List.of());
            assertTrue(trash.sweep(jda).contains("中止"));
            var channel = mock(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class);
            when(guild.getTextChannelsByName("チラシの裏", false)).thenReturn(List.of(channel, channel));
            assertTrue(trash.sweep(jda).contains("中止"));
            verify(guild, never()).retrieveActiveThreads();
            verifyNoInteractions(channel);
        }
    }

    @Test void unapprovedManualCallerCannotStartASweep() throws Exception {
        var event = mock(net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent.class);
        var reply = mock(net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction.class, RETURNS_SELF);
        when(event.getName()).thenReturn("gvd");
        when(event.getSubcommandName()).thenReturn("trash");
        var guild = mock(Guild.class); when(guild.getId()).thenReturn("guild");
        when(event.getGuild()).thenReturn(guild);
        Member denied = AiAccessTest.member(false);
        when(event.getMember()).thenReturn(denied);
        when(event.reply(anyString())).thenReturn(reply);
        try (var trash = new TrashMessages("guild", new AiAccess(directory, AiAccessTest.ROLE))) {
            trash.onSlashCommandInteraction(event);
            verify(reply).setEphemeral(true);
            verify(event, never()).deferReply(anyBoolean());
            verify(guild, never()).getTextChannelsByName(anyString(), anyBoolean());
        }
    }

}

