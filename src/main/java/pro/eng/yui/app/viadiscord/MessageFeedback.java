package pro.eng.yui.app.viadiscord;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.RestAction;

/** Per-message progress; serializes REST actions without delaying AI or Discord output. */
final class MessageFeedback implements OpenAiTransport.Progress, AutoCloseable {
    private final Message message;
    private CompletableFuture<Void> pending=CompletableFuture.completedFuture(null);
    private String current;
    private List<String> sources=List.of();
    @Override public void sources(List<String> urls) {sources=List.copyOf(urls);}
    String sourceNote(String body) {return SourceNotes.format(sources,body);}
    private boolean closed, deferred;
    private final java.util.function.Consumer<MessageFeedback> cleared;

    MessageFeedback(Message message) {this(message,ignored->{});}
    MessageFeedback(Message message,java.util.function.Consumer<MessageFeedback> cleared) {this.message=message;this.cleared=cleared;}
    void pickedUp() {show("👀");}
    @Override public void sent() {show("📨");}
    @Override public void receiving() {show("⤵️");}
    @Override public void failed() {show("❌");}

    private synchronized void show(String emoji) {
        if(closed||emoji.equals(current))return;
        // Every transition adds the next reaction before removing the previous one.
        enqueue(()->message.addReaction(Emoji.fromUnicode(emoji)));
        if(current!=null) {
            String previous=current;
            enqueue(()->message.removeReaction(Emoji.fromUnicode(previous)));
        }
        current=emoji;
    }
    private void enqueue(Supplier<RestAction<Void>> operation) {
        pending=pending.thenCompose(ignored->{
            try {return operation.get().submit();}
            catch(RuntimeException failure) {return CompletableFuture.<Void>failedFuture(failure);}
        }).handle((ignored,failure)->{
            if(failure!=null)FailureDiagnostics.log("PROGRESS_REACTION",failure,message.getId());
            return null;
        });
    }
    synchronized CompletableFuture<Void> clear() {
        if(!closed) {
            closed=true;
            cleared.accept(this);
            if(current!=null) {
                String previous=current;
                enqueue(()->message.removeReaction(Emoji.fromUnicode(previous)));
                current=null;
            }
        }
        return pending;
    }
    void reply(String text) {FailureDiagnostics.log("AI_ERROR_REPLY",null,message.getId());failed();reply(text,()->{});}
    void reply(String text,Runnable completed) {
        reply(text,completed,ignored->{});
    }
    void reply(String text,Runnable completed,java.util.function.Consumer<Message> delivered,
               net.dv8tion.jda.api.components.MessageTopLevelComponent... components) {
        deferred=true;
        sendChunks(DiscordText.split(text),0,completed,delivered,components);
    }
    private void sendChunks(List<String> chunks,int index,Runnable completed,java.util.function.Consumer<Message> delivered,
                            net.dv8tion.jda.api.components.MessageTopLevelComponent... components) {
        try {
            var action=message.reply(chunks.get(index))
                    .mentionRepliedUser(false).setAllowedMentions(List.of())
                    .setSuppressedNotifications(true).failOnInvalidReply(true);
            if(index==chunks.size()-1 && components.length>0)action.addComponents(components);
            action.queue(sent->{
                try {
                    delivered.accept(sent);
                    if(index+1<chunks.size())sendChunks(chunks,index+1,completed,delivered,components);
                    else {try {completed.run();} finally {clear();}}
                } catch(RuntimeException failure) {
                    FailureDiagnostics.log("AI_SEND",failure,message.getId());clear();
                }
            },failure->{
                FailureDiagnostics.log("AI_SEND",failure,message.getId());clear();
            });
        } catch(RuntimeException failure) {FailureDiagnostics.log("AI_SEND",failure,message.getId());deferred=false;clear();throw failure;}
    }
    @Override public void close() {if(!deferred)clear();}
}
