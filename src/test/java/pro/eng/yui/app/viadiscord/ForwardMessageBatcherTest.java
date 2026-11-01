package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.junit.jupiter.api.Test;

class ForwardMessageBatcherTest {
    static MessageReceivedEvent event(String id, boolean forwarded, String user, String channel, long millis) {
        var event=mock(MessageReceivedEvent.class, RETURNS_DEEP_STUBS);
        when(event.getGuild().getId()).thenReturn("guild");
        when(event.getChannel().getId()).thenReturn(channel);
        when(event.getAuthor().getId()).thenReturn(user);
        when(event.getMessage().getId()).thenReturn(id);
        when(event.getMessage().getContentRaw()).thenReturn(forwarded?"":"説明して");
        when(event.getMessage().getAttachments()).thenReturn(List.of());
        when(event.getMessage().getMessageReference()).thenReturn(null);
        when(event.getMessage().getMessageSnapshots()).thenReturn(forwarded?List.of(MessageInputTest.snapshot("引用")):List.of());
        when(event.getMessage().getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-10-31T00:00:00Z").plusNanos(millis*1_000_000));
        return event;
    }
    @Test void pairsBothArrivalOrdersOnceWithoutWaitingForTimeout() throws Exception {
        for(boolean firstForward:List.of(false,true)) {
            var output=new LinkedBlockingQueue<List<MessageReceivedEvent>>();
            var a=event("a",firstForward,"u","c",0);var b=event("b",!firstForward,"u","c",10);
            try(var batcher=new ForwardMessageBatcher(output::add,100)) {
                batcher.accept(a);assertTrue(output.isEmpty());batcher.accept(b);
                assertEquals(List.of(a,b),output.poll(1,TimeUnit.SECONDS));
                assertNull(output.poll(150,TimeUnit.MILLISECONDS));
            }
        }
    }
    @Test void standaloneForwardAndOrdinaryMessageAreEventuallyDispatched() throws Exception {
        var output=new LinkedBlockingQueue<List<MessageReceivedEvent>>();
        try(var batcher=new ForwardMessageBatcher(output::add,30)) {
            for(boolean forwarded:List.of(false,true)) {
                var a=event("a"+forwarded,forwarded,"u","c",0);batcher.accept(a);
                assertEquals(List.of(a),output.poll(1,TimeUnit.SECONDS));
            }
        }
    }
    @Test void ordinaryPairsForwardsOtherUsersChannelsRepliesAndOldMessagesStaySeparate() {
        var note=event("a",false,"u","c",0);var forward=event("b",true,"u","c",10);
        assertTrue(ForwardMessageBatcher.matches(note,forward,100));
        assertFalse(ForwardMessageBatcher.matches(note,event("b",false,"u","c",10),100));
        assertFalse(ForwardMessageBatcher.matches(forward,event("b",true,"u","c",10),100));
        assertFalse(ForwardMessageBatcher.matches(note,event("b",true,"other","c",10),100));
        assertFalse(ForwardMessageBatcher.matches(note,event("b",true,"u","other",10),100));
        assertFalse(ForwardMessageBatcher.matches(note,event("b",true,"u","c",200),100));
        when(note.getMessage().getMessageReference()).thenReturn(mock(MessageReference.class));
        assertFalse(ForwardMessageBatcher.matches(note,forward,100));
        when(note.getMessage().getMessageReference()).thenReturn(null);
        when(note.getMessage().getAttachments()).thenReturn(List.of(mock(Message.Attachment.class)));
        assertFalse(ForwardMessageBatcher.matches(note,forward,100));
    }
    @Test void interveningUserBreaksPairAndDuplicateDeliveryDoesNotDispatchTwice() throws Exception {
        var output=new LinkedBlockingQueue<List<MessageReceivedEvent>>();
        var a=event("a",false,"u","c",0);var b=event("b",false,"other","c",1);var c=event("c",true,"u","c",2);
        try(var batcher=new ForwardMessageBatcher(output::add,100)) {
            batcher.accept(a);batcher.accept(a);assertTrue(output.isEmpty());batcher.accept(b);batcher.accept(c);
            assertEquals(List.of(a),output.poll(1,TimeUnit.SECONDS));
            assertEquals(List.of(b),output.poll(1,TimeUnit.SECONDS));
            assertEquals(List.of(c),output.poll(1,TimeUnit.SECONDS));
            assertTrue(output.isEmpty());
        }
    }
    @Test void closeCancelsPendingWork() throws Exception {
        var output=new LinkedBlockingQueue<List<MessageReceivedEvent>>();
        var batcher=new ForwardMessageBatcher(output::add,30);
        batcher.accept(event("a",true,"u","c",0));batcher.close();
        assertNull(output.poll(100,TimeUnit.MILLISECONDS));
    }
}
