package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;

class ServerModelContextTest {
    static Message message(String text) {
        var message = mock(Message.class);
        when(message.getContentRaw()).thenReturn(text);
        when(message.getType()).thenReturn(MessageType.DEFAULT);
        return message;
    }

    @SuppressWarnings("unchecked")
    static RestAction<List<Message>> document(Guild guild, String name, Message... newestFirst) {
        var channel = mock(TextChannel.class);
        var history = mock(MessageHistory.class);
        RestAction<List<Message>> request = mock(RestAction.class);
        when(guild.getTextChannelsByName(name, false)).thenReturn(List.of(channel));
        when(channel.getId()).thenReturn(name + "-id");
        when(channel.getHistory()).thenReturn(history);
        when(history.retrievePast(100)).thenReturn(request);
        when(request.complete()).thenReturn(List.of(newestFirst));
        return request;
    }

    static void documents(Guild guild) {
        document(guild, "readme", message("server background"));
        document(guild, "agents", message("server guidance"));
        document(guild, "aboutme", message("server user profile"));
    }

    @Test void readsAllDocumentsOldestFirstAndRefreshesEditsAndDeletes() throws Exception {
        var guild = mock(Guild.class);
        var readme = document(guild, "readme", message("newer"), message("older"));
        document(guild, "agents", message("guidance"));
        var aboutme = document(guild, "aboutme", message("old profile"));
        var initial = ServerModelContext.load(guild);
        assertTrue(initial.contains("older\nnewer\n"));
        assertTrue(initial.contains("guidance"));
        assertTrue(initial.contains("old profile"));
        var edited = message("edited");
        when(readme.complete()).thenReturn(List.of(edited));
        var updatedProfile = message("updated profile");
        when(aboutme.complete()).thenReturn(List.of(updatedProfile));
        var updated = ServerModelContext.load(guild);
        assertTrue(updated.contains("edited"));
        assertFalse(updated.contains("older"));
        assertTrue(updated.contains("updated profile"));
        assertFalse(updated.contains("old profile"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"agents", "aboutme"})
    void rejectsMissingDuplicateEmptyUnreadableAndOversizedDocuments(String name) throws Exception {
        var guild = mock(Guild.class);
        documents(guild);
        when(guild.getTextChannelsByName(name, false)).thenReturn(List.of());
        assertTrue(assertThrows(ServerModelContext.Unavailable.class, () -> ServerModelContext.load(guild)).getMessage().contains("#" + name));
        var duplicate = List.of(mock(TextChannel.class), mock(TextChannel.class));
        when(guild.getTextChannelsByName(name, false)).thenReturn(duplicate);
        assertThrows(ServerModelContext.Unavailable.class, () -> ServerModelContext.load(guild));
        document(guild, name, message(" "));
        assertThrows(ServerModelContext.Unavailable.class, () -> ServerModelContext.load(guild));
        document(guild, name, message("x".repeat(32000)));
        assertThrows(ServerModelContext.Unavailable.class, () -> ServerModelContext.load(guild));
        var request = document(guild, name, message("guidance"));
        when(request.complete()).thenThrow(new IllegalStateException("private error body"));
        assertFalse(assertThrows(ServerModelContext.Unavailable.class, () -> ServerModelContext.load(guild)).getMessage().contains("private error body"));
    }

    @Test void paginatesPastOneHundredPostsAndRejectsMoreThanFiveHundred() throws Exception {
        var guild = mock(Guild.class);
        var newest = message("newer");when(newest.getId()).thenReturn("123456789012345678");
        var page = java.util.Collections.nCopies(100, newest);
        documents(guild);
        document(guild, "readme", page.toArray(Message[]::new));
        document(guild, "agents", message("guidance"));
        var channel = guild.getTextChannelsByName("readme", false).getFirst();
        var request = mock(MessageHistory.MessageRetrieveAction.class);
        var history = mock(MessageHistory.class);
        when(channel.getHistoryBefore("123456789012345678", 100)).thenReturn(request);
        when(request.complete()).thenReturn(history);
        var older = message("oldest");
        when(history.getRetrievedHistory()).thenReturn(List.of(older));
        assertTrue(ServerModelContext.load(guild).contains("oldest\nnewer\n"));
        when(history.getRetrievedHistory()).thenReturn(page);
        assertThrows(ServerModelContext.Unavailable.class, () -> ServerModelContext.load(guild));
    }
}
