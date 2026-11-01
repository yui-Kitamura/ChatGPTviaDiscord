package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.messages.MessageSnapshot;
import org.junit.jupiter.api.Test;

class MessageInputTest {
    static MessageSnapshot snapshot(String text, Message.Attachment... attachments) {
        return new MessageSnapshot(MessageType.DEFAULT, mock(Mentions.class), null, text,
                List.of(attachments), List.of(), List.of(), List.of(), 0);
    }

    @Test void ordinaryMessageIsUnchangedAndAllForwardedCopiesAreIncluded() {
        var message = mock(Message.class);
        when(message.getContentRaw()).thenReturn("コメント");
        assertEquals("コメント", MessageInput.from(message).text());
        var first = mock(Message.Attachment.class);
        var second = mock(Message.Attachment.class);
        when(message.getAttachments()).thenReturn(List.of(first));
        when(message.getMessageSnapshots()).thenReturn(List.of(snapshot("転送本文", second), snapshot("次の転送")));
        var result = MessageInput.from(message);
        assertEquals("コメント\n[転送メッセージ・引用資料]\n転送本文\n[転送メッセージ・引用資料]\n次の転送", result.text());
        assertEquals(List.of(first, second), result.attachments());
    }

    @Test void pairKeepsNoteBeforeQuoteAndRetainsAllAttachmentsInBothOrders() {
        var note=mock(Message.class);var forward=mock(Message.class);var attachment=mock(Message.Attachment.class);
        when(note.getContentRaw()).thenReturn("依頼");when(forward.getContentRaw()).thenReturn("");
        when(forward.getMessageSnapshots()).thenReturn(List.of(snapshot("資料",attachment)));
        for(var messages:List.of(List.of(note,forward),List.of(forward,note))) {
            var result=MessageInput.combine(messages);
            assertEquals("依頼\n[転送メッセージ・引用資料]\n資料",result.text());
            assertEquals(List.of(attachment),result.attachments());
        }
    }

    @Test void emptySnapshotDoesNotPretendToHaveReadableContent() {
        var message = mock(Message.class);
        when(message.getContentRaw()).thenReturn("");
        when(message.getMessageSnapshots()).thenReturn(List.of(snapshot("")));
        assertTrue(MessageInput.from(message).text().isBlank());
        assertTrue(MessageInput.from(message).attachments().isEmpty());
    }

    @Test void forwardedEmbedTextIsReadable() {
        var message = mock(Message.class);
        when(message.getContentRaw()).thenReturn("");
        var embed = new net.dv8tion.jda.api.EmbedBuilder()
                .setTitle("見出し").setDescription("説明").addField("項目", "値", false).build();
        var snapshot = new MessageSnapshot(MessageType.DEFAULT, mock(Mentions.class), null, "",
                List.of(), List.of(embed), List.of(), List.of(), 0);
        when(message.getMessageSnapshots()).thenReturn(List.of(snapshot));
        assertEquals("[転送メッセージ・引用資料]\n見出し\n説明\n項目\n値", MessageInput.from(message).text());
    }
}
