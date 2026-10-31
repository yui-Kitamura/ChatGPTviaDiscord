package pro.eng.yui.app.viadiscord;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.FileUpload;

/** Per-message progress; serializes REST actions without delaying AI or Discord output. */
final class MessageFeedback implements OpenAiTransport.Progress, AutoCloseable {
    private final Message message;
    private CompletableFuture<Void> pending=CompletableFuture.completedFuture(null);
    private String current;
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
        var action=message.reply(text.length()<=1900?text:"回答を添付しました。")
                .mentionRepliedUser(false).setAllowedMentions(List.of())
                .setSuppressedNotifications(true).failOnInvalidReply(true);
        if(text.length()>1900)action.addFiles(FileUpload.fromData(text.getBytes(StandardCharsets.UTF_8),"response.txt"));
        deferred=true;
        try {
            action.queue(ignored->{try {completed.run();} finally {clear();}},failure->{
                FailureDiagnostics.log("AI_SEND",failure,message.getId());
                clear();
            });
        } catch(RuntimeException failure) {FailureDiagnostics.log("AI_SEND",failure,message.getId());deferred=false;throw failure;}
    }
    @Override public void close() {if(!deferred)clear();}
}
