package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscordReplyContextTest {
    @TempDir Path directory;
    final String key="123-abc";
    Message source=mock(Message.class),target=mock(Message.class);
    MessageReference reference=mock(MessageReference.class);

    void reply() {
        when(source.getType()).thenReturn(MessageType.INLINE_REPLY);
        when(source.getContentRaw()).thenReturn("詳しく教えて");
        when(source.getChannelId()).thenReturn("123");
        when(source.getMessageReference()).thenReturn(reference);
        when(reference.getMessageId()).thenReturn("456");
        when(reference.getChannelId()).thenReturn("123");
        when(target.getId()).thenReturn("456");when(target.getChannelId()).thenReturn("123");
        var author=mock(User.class);when(author.getId()).thenReturn("human");when(target.getAuthor()).thenReturn(author);
        var jda=mock(JDA.class);var self=mock(SelfUser.class);when(self.getId()).thenReturn("bot");
        when(jda.getSelfUser()).thenReturn(self);when(source.getJDA()).thenReturn(jda);
        when(target.getContentRaw()).thenReturn("元の本文");when(target.getAttachments()).thenReturn(List.of());
    }
    com.fasterxml.jackson.databind.JsonNode data(String input) throws Exception {
        return ChatGptAccounts.JSON.readTree(input.substring(input.indexOf('{')));
    }
    @Test void ordinaryMessageIsUnchanged() throws Exception {
        when(source.getContentRaw()).thenReturn("本文");
        assertEquals("本文",DiscordReplyContext.input(source,new ChatHistory(directory),key));
        verify(source,never()).getReferencedMessage();
    }
    @Test void cachedHumanReplyIsQuotedWithIdentity() throws Exception {
        reply();when(source.getReferencedMessage()).thenReturn(target);
        var input=DiscordReplyContext.input(source,new ChatHistory(directory),key);
        assertTrue(input.startsWith("詳しく教えて\n"));var data=data(input);
        assertEquals("元の本文",data.path("body").asText());assertEquals("456",data.path("message_id").asText());
        assertFalse(data.has("thread_posts"));verify(reference,never()).resolve();
    }
    @Test void cacheMissRetrievesMessage() throws Exception {
        reply();@SuppressWarnings("unchecked") RestAction<Message> action=mock(RestAction.class,RETURNS_SELF);
        when(reference.resolve()).thenReturn(action);when(action.complete()).thenReturn(target);
        assertEquals("resolved",data(DiscordReplyContext.input(source,new ChatHistory(directory),key)).path("status").asText());
        verify(action).complete();
    }
    @Test void failedLookupDoesNotInventBodyOrPreventInput() throws Exception {
        reply();when(reference.resolve()).thenThrow(new IllegalStateException("private details"));
        var input=DiscordReplyContext.input(source,new ChatHistory(directory),key);var data=data(input);
        assertEquals("unavailable",data.path("status").asText());assertFalse(data.has("body"));assertFalse(input.contains("private details"));
    }
    @Test void crossChannelReferenceIsNotRead() throws Exception {
        reply();when(reference.getChannelId()).thenReturn("other");
        assertEquals("unavailable",data(DiscordReplyContext.input(source,new ChatHistory(directory),key)).path("status").asText());
        verify(source,never()).getReferencedMessage();verify(reference,never()).resolve();
    }
    @Test void savedAttachmentAnswerIncludesAllNumbersAndCharactersAfterReopen() throws Exception {
        reply();when(source.getReferencedMessage()).thenReturn(target);when(target.getAuthor().getId()).thenReturn("bot");
        when(target.getContentRaw()).thenReturn("回答を添付しました。");
        var attachment=mock(Message.Attachment.class);when(attachment.getId()).thenReturn("789");when(attachment.getFileName()).thenReturn("response.txt");
        when(target.getAttachments()).thenReturn(List.of(attachment));
        String body="AI による掛け合い\n\n12：名無しの案内人\n解説\n\n13：名無しの観察者\n感想\n";
        new ChatHistory(directory).saveReply(key,"456",body);
        var data=data(DiscordReplyContext.input(source,new ChatHistory(directory),key));
        assertEquals(body,data.path("body").asText());assertEquals(2,data.path("thread_posts").size());
        assertEquals("12",data.path("thread_posts").get(0).path("post_number").asText());
        assertEquals("観察者",data.path("thread_posts").get(1).path("character").asText());
    }
    @Test void individualThreadPostIncludesNumberAndCharacterAndSavedLongBody() throws Exception {
        reply();when(source.getReferencedMessage()).thenReturn(target);when(target.getAuthor().getId()).thenReturn("bot");
        when(target.getContentRaw()).thenReturn("42：案内人 <@&123456789012345678>\n本文を添付しました。");
        var attachment=mock(Message.Attachment.class);when(attachment.getId()).thenReturn("789");when(attachment.getFileName()).thenReturn("post-42.txt");
        when(target.getAttachments()).thenReturn(List.of(attachment));
        String body="42：案内人 <@&123456789012345678>\n詳しい解説";
        new ChatHistory(directory).saveReply(key,"456",body);
        var data=data(DiscordReplyContext.input(source,new ChatHistory(directory),key));
        assertEquals(body,data.path("body").asText());assertEquals("42",data.path("thread_posts").get(0).path("post_number").asText());
        assertEquals("案内人",data.path("thread_posts").get(0).path("character").asText());
    }
    @Test void longBodyIsBounded() throws Exception {
        reply();when(source.getReferencedMessage()).thenReturn(target);when(target.getContentRaw()).thenReturn("あ".repeat(20000));
        var data=data(DiscordReplyContext.input(source,new ChatHistory(directory),key));
        assertEquals(16000,data.path("body").asText().length());assertTrue(data.path("body_truncated").asBoolean());
    }
}
