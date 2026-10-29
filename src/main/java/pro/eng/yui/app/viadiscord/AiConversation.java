package pro.eng.yui.app.viadiscord;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.utils.FileUpload;

final class AiConversation implements java.util.function.Consumer<MessageReceivedEvent>,AutoCloseable {
    private final AiAccess access;
    private final ChannelControls controls;
    private final ChatGptSession session;
    private final OpenAiTransport transport;
    private final ResponseSettings settings;
    private final ChatHistory history;
    private final String instructions;
    private final java.util.function.Function<net.dv8tion.jda.api.entities.Guild,CharacterRoles.RoleGateway> roleGateway;
    private CharacterRoles characterRoles;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),Thread.ofVirtual().factory(),new ThreadPoolExecutor.AbortPolicy());
    AiConversation(AiAccess access,ChannelControls controls,ChatGptSession session,OpenAiTransport transport,ResponseSettings settings,ChatHistory history,String instructions) {
        this(access,controls,session,transport,settings,history,instructions,CharacterRoles::discord);
    }
    AiConversation(AiAccess access,ChannelControls controls,ChatGptSession session,OpenAiTransport transport,ResponseSettings settings,ChatHistory history,String instructions,
                   java.util.function.Function<net.dv8tion.jda.api.entities.Guild,CharacterRoles.RoleGateway> roleGateway) {
        this.access=access;this.controls=controls;this.session=session;this.transport=transport;this.settings=settings;this.history=history;this.instructions=instructions;
        this.roleGateway=roleGateway;
    }
    @Override public void accept(MessageReceivedEvent event) {
        if(!access.permits(event.getMember()))return;
        try{worker.submit(()->process(event));}
        catch(RejectedExecutionException e){reply(event.getMessage(),"処理待ちが多いため受け付けられませんでした。後で再投稿してください。");}
    }
    private void process(MessageReceivedEvent event) {
        Message message=event.getMessage();String user=event.getAuthor().getId();
        String parent=event.getChannelType().isThread()?event.getChannel().asThreadChannel().getParentChannel().getId():null;
        if(!access.permits(event.getGuild().getMemberById(user))||!controls.shouldRespond(event.getGuild().getId(),event.getChannel().getId(),parent,false,false,message.getType()))return;
        try {
            var account=session.ready(event.getGuild().getId());
            if(account.model.isEmpty())throw new ChatGptException("model_required",0);
            String content=message.getContentRaw();
            if(content.isBlank()){reply(message,"本文が空か、読み取れませんでした。テキスト本文とMessage Content Intentを確認してください。添付だけの投稿は未対応です。");return;}
            if(content.length()>16000){reply(message,"本文が長いため、分割して投稿してください。");return;}
            String key=event.getChannel().getId()+"-"+UUID.nameUUIDFromBytes((account.clientId+":"+account.subject).getBytes(StandardCharsets.UTF_8));
            var saved=history.load(key);if(saved.ids.contains(message.getId()))return;
            var input=new ArrayList<>(saved.turns);input.add(Map.of("role","user","content",content));
            message.addReaction(Emoji.fromUnicode("👀")).queue(ignored->{},error->{});
            var context=ResponseContext.prepare(settings,event.getChannel().getId(),message.getMentions().getRoles().stream().map(r->r.getId()).collect(java.util.stream.Collectors.toSet()));
            String prompt=instructions+"\n"+context.instructions()+"\n利用できるのは渡された直近の会話だけです。Web閲覧・記憶検索・外部操作ツールはありません。";
            prompt+=CharacterReply.instructions(context,saved.nextPost);
            String response=transport.respond(account.accessToken,account.model,prompt,input);
            if(!session.isCurrent(event.getGuild().getId(),account)||!access.permits(event.getGuild().getMemberById(user))||!controls.shouldRespond(event.getGuild().getId(),event.getChannel().getId(),parent,false,false,message.getType()))return;
            String display=response;
            if(context.mode()==ResponseMode.THREAD) {
                final CharacterReply generated;
                try {generated=CharacterReply.parse(response,context,saved.nextPost);}
                catch(java.io.IOException|IllegalArgumentException e) {
                    reply(message,"キャラ応答の形式を確認できなかったため、ロール作成・回答の確定は行っていません。自動再試行はしていません。");return;
                }
                if(characterRoles==null)characterRoles=new CharacterRoles(settings,roleGateway.apply(event.getGuild()));
                try {characterRoles.ensureCharacters(generated.characters(),()->{
                    try {return session.isCurrent(event.getGuild().getId(),account)&&access.permits(event.getGuild().getMemberById(user))
                            &&settings.mode(event.getChannel().getId())==context.mode()
                            &&controls.shouldRespond(event.getGuild().getId(),event.getChannel().getId(),parent,false,false,message.getType());}
                    catch(java.io.IOException e){return false;}
                });}
                catch(java.io.IOException|RuntimeException e) {
                    reply(message,"キャラのロール作成・保存を完了できませんでした。Bot のロール管理権限・階層・保存先と利用許可を確認してください。回答は確定していません。");return;
                }
                if(!session.isCurrent(event.getGuild().getId(),account)||!access.permits(event.getGuild().getMemberById(user)))return;
                display=generated.display(saved.nextPost);saved.nextPost+=generated.posts().size();
            }
            saved.turns=input;saved.turns.add(Map.of("role","assistant","content",response));saved.ids.add(message.getId());
            history.save(key,saved);
            reply(message,display);
        }catch(ChatGptException error){error.log();reply(message,error.userMessage());}
        catch(Exception error){System.err.println("GVD_AI_FAILED error_type="+error.getClass().getSimpleName());reply(message,"応答または会話の保存を完了できませんでした。時間を置いて再投稿してください。自動再試行はしていません。");}
    }
    private static void reply(Message message,String text){
        var action=message.reply(text.length()<=1900?text:"回答を添付しました。").mentionRepliedUser(false).setAllowedMentions(List.of()).setSuppressedNotifications(true).failOnInvalidReply(true);
        if(text.length()>1900)action.addFiles(FileUpload.fromData(text.getBytes(StandardCharsets.UTF_8),"response.txt"));
        action.queue(ignored->{},failure->System.err.println("GVD_AI_SEND_FAILED error_type="+failure.getClass().getSimpleName()));
    }
    @Override public void close(){worker.shutdownNow();}
}
