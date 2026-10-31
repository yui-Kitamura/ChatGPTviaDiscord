package pro.eng.yui.app.viadiscord;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
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
    static final String RETRY_PREFIX="gvd:thread-retry:";
    private static final long RETRY_TTL_MILLIS=TimeUnit.MINUTES.toMillis(15);
    private final Map<String,RetryRequest> retries=new LinkedHashMap<>();
    private record RetryRequest(MessageReceivedEvent original,String replyId,long expiresAt,AtomicBoolean claimed) {}
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
    private void process(MessageReceivedEvent event) {process(event,false);}
    private void process(MessageReceivedEvent event,boolean retry) {
        Message message=event.getMessage();String user=event.getAuthor().getId();
        String parent=event.getChannelType().isThread()?event.getChannel().asThreadChannel().getParentChannel().getId():null;
        if(!access.permits(event.getGuild().getMemberById(user))||!controls.shouldRespond(event.getGuild().getId(),event.getChannel().getId(),parent,false,false,message.getType()))return;
        try {
            if(retry&&settings.mode(event.getChannel().getId())!=ResponseMode.THREAD)return;
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
                    replyWithRetry(event);return;
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
    private void replyWithRetry(MessageReceivedEvent event) {
        String id=RETRY_PREFIX+UUID.randomUUID();
        event.getMessage().reply("キャラ応答の形式を確認できなかったため、ロール作成・回答の確定は行っていません。自動再試行はしていません。\n投稿者は15分以内に「再送」を押すと、同じ依頼を再試行できます（Bot再起動後は無効）。")
                .mentionRepliedUser(false).setAllowedMentions(List.of()).setSuppressedNotifications(true).failOnInvalidReply(true)
                .addComponents(ActionRow.of(Button.primary(id,"再送")))
                .queue(sent->{synchronized(retries){
                    retries.values().removeIf(r->r.expiresAt()<System.currentTimeMillis());
                    while(retries.size()>=128)retries.remove(retries.keySet().iterator().next());
                    retries.put(id,new RetryRequest(event,sent.getId(),System.currentTimeMillis()+RETRY_TTL_MILLIS,new AtomicBoolean()));
                }},failure->System.err.println("GVD_AI_SEND_FAILED error_type="+failure.getClass().getSimpleName()));
    }
    void retry(ButtonInteractionEvent event) {
        String id=event.getComponentId();
        if(!id.startsWith(RETRY_PREFIX))return;
        RetryRequest request;
        synchronized(retries){
            retries.values().removeIf(r->r.expiresAt()<System.currentTimeMillis());
            request=retries.get(id);
        }
        if(request==null){retryNotice(event,"再送ボタンは期限切れ、受付済み、またはBot再起動により無効です。元の依頼を再投稿してください。");return;}
        var original=request.original();
        if(!event.isFromGuild()||!event.getGuild().getId().equals(original.getGuild().getId())
                ||!event.getChannel().getId().equals(original.getChannel().getId())||!event.getMessageId().equals(request.replyId())
                ||!event.getUser().getId().equals(original.getAuthor().getId())||!access.permits(event.getMember())) {
            retryNotice(event,"この再送ボタンは、利用権限のある元の投稿者だけが使えます。");return;
        }
        String parent=original.getChannelType().isThread()?original.getChannel().asThreadChannel().getParentChannel().getId():null;
        if(settings.mode(original.getChannel().getId())!=ResponseMode.THREAD
                ||!controls.shouldRespond(original.getGuild().getId(),original.getChannel().getId(),parent,false,false,original.getMessage().getType())) {
            retryNotice(event,"現在のモードまたはチャンネル設定では再送できません。");return;
        }
        if(!request.claimed().compareAndSet(false,true)){retryNotice(event,"この依頼は再送を受け付けています。");return;}
        try {
            event.deferReply(true).queue(hook->{
                try {
                    worker.execute(()->process(original,true));
                    synchronized(retries){retries.remove(id);}
                }catch(RejectedExecutionException failure){
                    request.claimed().set(false);
                    hook.editOriginal("処理待ちが多いため受け付けられませんでした。後で同じ再送ボタンを押してください。").queue();return;
                }
                hook.editOriginal("再送を受け付けました。結果は元の投稿への返信でお知らせします。").queue();
                event.getMessage().editMessageComponents(List.of()).queue(ignored->{},failure->{});
            },failure->request.claimed().set(false));
        }catch(RuntimeException failure){request.claimed().set(false);}
    }
    private static void retryNotice(ButtonInteractionEvent event,String text){event.reply(text).setEphemeral(true).queue();}
    private static void reply(Message message,String text){
        var action=message.reply(text.length()<=1900?text:"回答を添付しました。").mentionRepliedUser(false).setAllowedMentions(List.of()).setSuppressedNotifications(true).failOnInvalidReply(true);
        if(text.length()>1900)action.addFiles(FileUpload.fromData(text.getBytes(StandardCharsets.UTF_8),"response.txt"));
        action.queue(ignored->{},failure->System.err.println("GVD_AI_SEND_FAILED error_type="+failure.getClass().getSimpleName()));
    }
    @Override public void close(){worker.shutdownNow();synchronized(retries){retries.clear();}}
}
