package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.Test;

class MessageFeedbackTest {
    final Message message=mock(Message.class);
    final List<String> operations=new ArrayList<>();
    final MessageCreateAction reply=mock(MessageCreateAction.class,RETURNS_SELF);
    Consumer<Message> delivered;
    Consumer<Throwable> rejected;

    @SuppressWarnings("unchecked") MessageFeedbackTest() {
        when(message.addReaction(any(Emoji.class))).thenAnswer(i->action("+"+i.<Emoji>getArgument(0).getName()));
        when(message.removeReaction(any(Emoji.class))).thenAnswer(i->action("-"+i.<Emoji>getArgument(0).getName()));
        when(message.reply(anyString())).thenReturn(reply);
        doAnswer(i->{delivered=i.getArgument(0);rejected=i.getArgument(1);return null;}).when(reply).queue(any(),any());
    }
    @SuppressWarnings("unchecked") RestAction<Void> action(String operation) {
        var action=(RestAction<Void>)mock(RestAction.class);
        when(action.submit()).thenAnswer(i->{operations.add(operation);return CompletableFuture.completedFuture(null);});
        return action;
    }
    @Test void progressRemainsUntilAsyncOutputFinishesAndOnlyOwnReactionsAreRemoved() {
        var feedback=new MessageFeedback(message);
        feedback.pickedUp();feedback.sent();feedback.receiving();
        feedback.reply("answer",()->{});feedback.close();
        assertEquals(List.of("+👀","+📨","-👀","-📨","+⤵️"),operations);
        delivered.accept(message);
        assertEquals("-⤵️",operations.getLast());
        verify(message,never()).clearReactions();
    }
    @Test void errorMarkerIsRemovedAfterErrorOutputAndSendFailureAlsoCleansUp() {
        for(boolean success:List.of(false,true)) {
            operations.clear();var feedback=new MessageFeedback(message);
            feedback.pickedUp();feedback.sent();feedback.reply("error");feedback.close();
            assertEquals(List.of("+👀","+📨","-👀","-📨","+❌"),operations);
            if(success)delivered.accept(message);else rejected.accept(new IllegalStateException());
            assertEquals("-❌",operations.getLast());
        }
    }
    @Test void slowDiscordRequestsStayOrderedEvenIfOutputAlreadyFinished() throws Exception {
        var first=new CompletableFuture<Void>();
        var add=action("unused");doReturn(first).when(add).submit();
        when(message.addReaction(argThat(e->"👀".equals(e.getName())))).thenReturn(add);
        var feedback=new MessageFeedback(message);
        feedback.pickedUp();feedback.sent();feedback.receiving();var cleared=feedback.clear();
        assertTrue(operations.isEmpty());assertFalse(cleared.isDone());
        first.complete(null);cleared.get(1,TimeUnit.SECONDS);
        assertEquals(List.of("+📨","-👀","-📨","+⤵️","-⤵️"),operations);
        feedback.receiving();assertEquals(5,operations.size());
    }
    @Test void eyesAreRemovedOnlyAfterEnvelopeAdditionCompletes() throws Exception {
        var mailed=new CompletableFuture<Void>();
        var add=action("unused");
        doAnswer(i->{operations.add("+📨");return mailed;}).when(add).submit();
        when(message.addReaction(argThat(e->"📨".equals(e.getName())))).thenReturn(add);
        var feedback=new MessageFeedback(message);
        feedback.pickedUp();feedback.sent();feedback.receiving();
        assertEquals(List.of("+👀","+📨"),operations);
        verify(message,never()).removeReaction(any(Emoji.class));
        mailed.complete(null);feedback.clear().get(1,TimeUnit.SECONDS);
        assertEquals(List.of("+👀","+📨","-👀","-📨","+⤵️","-⤵️"),operations);
    }
    @Test void reactionFailureDoesNotBlockLaterTransitionsOrCleanup() throws Exception {
        var add=action("unused");doReturn(CompletableFuture.failedFuture(new IllegalStateException())).when(add).submit();
        when(message.addReaction(argThat(e->"👀".equals(e.getName())))).thenReturn(add);
        var feedback=new MessageFeedback(message);
        feedback.pickedUp();feedback.sent();feedback.close();feedback.clear().get(1,TimeUnit.SECONDS);
        assertEquals(List.of("+📨","-👀","-📨"),operations);
    }
    @Test void synchronousOutputFailureAndEarlyExitCleanUp() {
        var feedback=new MessageFeedback(message);feedback.pickedUp();
        try(feedback) {when(message.reply(anyString())).thenThrow(new IllegalStateException());assertThrows(IllegalStateException.class,()->feedback.reply("answer",()->{}));}
        assertEquals(List.of("+👀","-👀"),operations);
    }
}
