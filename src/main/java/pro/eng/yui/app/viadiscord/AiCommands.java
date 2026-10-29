package pro.eng.yui.app.viadiscord;

import java.util.*;
import java.util.concurrent.*;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.FileUpload;

final class AiCommands extends ListenerAdapter {
    private final String guild;
    private final AiConfig config;
    private final AiAccess access;
    private final ChatGptAccounts accounts;
    private final ChatGptSession session;
    private final OpenAiTransport transport;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),Thread.ofVirtual().factory(),new ThreadPoolExecutor.AbortPolicy());
    AiCommands(String guild,AiConfig config,AiAccess access,ChatGptAccounts accounts,ChatGptSession session,OpenAiTransport transport){this.guild=guild;this.config=config;this.access=access;this.accounts=accounts;this.session=session;this.transport=transport;}
    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if(!"gvd".equals(event.getName())||event.getUser().isBot())return;
        String sub=event.getSubcommandName();
        if(!Set.of("auth","model","accept","reject").contains(Objects.toString(sub,"")))return;
        if(!event.isFromGuild()||!guild.equals(event.getGuild().getId())){event.reply("このサーバーでは利用できません。").setEphemeral(true).queue();return;}
        if("accept".equals(sub)||"reject".equals(sub)){roles(event,sub);return;}
        if(!config.enabled()||!access.permits(event.getMember())){
            event.reply(config.enabled()?"AIの利用には許可ロールが必要です。":"ChatGPT接続は無効です。VPSでCHATGPT_ENABLED=trueと必要なGateway Intentを設定してください。").setEphemeral(true).queue();return;
        }
        if(event.getMember()==null||!event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("Bot共通のChatGPT接続・モデル設定にはサーバー管理権限が必要です。").setEphemeral(true).queue();return;
        }
        event.deferReply(true).queue(hook->{
            try{worker.submit(()->{
                try {
                    // A queued command must not retain access after its role was removed.
                    if(!access.permits(event.getGuild().getMemberById(event.getUser().getId())) || !event.getGuild().getMemberById(event.getUser().getId()).hasPermission(Permission.MANAGE_SERVER)) {say(hook,"許可ロールがなくなったため処理を中止しました。");return;}
                    if("model".equals(sub)){models(event,hook);return;}
                    String user=guild;String actor=event.getUser().getId();String label=option(event,"account","default");
                    String action=option(event,"action","login");String key=ChatGptAccounts.key(user,label);
                    switch(action) {
                        case "login" -> {
                            String url=session.login(user,label,()->access.permits(event.getGuild().getMemberById(actor)) && event.getGuild().getMemberById(actor).hasPermission(Permission.MANAGE_SERVER),text->say(hook,text));
                            say(hook,"認証前にPCからVPSへSSHトンネルを開いてください（README参照、ローカル/リモートともポート"+config.callbackPort()+"）。\n[Continue with ChatGPT]("+url+")\nリンクは10分間有効です。ChatGPTプランの利用枠を使用します。過去のChatGPT会話・メモリは取り込みません。");
                        }
                        case "status" -> {
                            var lines=new ArrayList<String>();String active=accounts.activeKey(user);
                            for(var entry:accounts.list(user).entrySet()) {
                                var a=entry.getValue();lines.add((keyFor(user,entry.getKey()).equals(active)?"* ":"  ")+entry.getKey()+" / "+a.email+" / "+(a.accessToken.isEmpty()?"未接続":a.canInvoke()?"プラン利用許可あり":"プラン利用許可なし")+" / model="+a.model);
                            }
                            say(hook,lines.isEmpty()?"保存済みアカウントはありません。/gvd auth からログインしてください。":String.join("\n",lines)+"\n利用枠: https://chatgpt.com/settings/usage");
                        }
                        case "use" -> {accounts.select(user,key);say(hook,"使用するChatGPTアカウントを "+label+" に変更しました。");}
                        case "logout" -> say(hook,session.logout(user,label));
                        default -> say(hook,"action は login/status/use/logout です。");
                    }
                }catch(ChatGptException e){e.log();say(hook,e.userMessage());}
                catch(IllegalArgumentException e){say(hook,"設定値を確認してください。account は英数字・_・-の1〜32文字です。");}
                catch(Exception e){say(hook,"処理を完了できませんでした。設定と接続を確認して再実行してください。");}
            });}catch(RejectedExecutionException e){say(hook,"処理が混み合っています。後で再実行してください。");}
        });
    }
    private static String keyFor(String user,String label){return ChatGptAccounts.key(user,label);}
    private void roles(SlashCommandInteractionEvent event,String sub) {
        var option=event.getOption("role");
        if(option==null&&"accept".equals(sub)) {
            event.deferReply(true).queue(hook->{
                if(!config.enabled()){say(hook,"登録ロール: "+access.roleIds()+"\n全利用者の取得にはCHATGPT_ENABLED=trueで再起動してください。");return;}
                event.getGuild().loadMembers().onSuccess(members->{
                    String configured=access.roleIds().stream().sorted().map(id->"<@&"+id+">").collect(java.util.stream.Collectors.joining(" "));
                    String list=members.stream().filter(access::permits).sorted(Comparator.comparing(m->m.getId()))
                            .map(m->m.getEffectiveName()+" ("+m.getId()+")").collect(java.util.stream.Collectors.joining("\n"));
                    say(hook,"登録ロール: "+(configured.isEmpty()?"なし":configured)+"\n現在使える人:\n"+(list.isEmpty()?"なし":list));
                }).onError(error->say(hook,"メンバー一覧を取得できませんでした。Server Members Intentを確認してください。"));
            });return;
        }
        if(event.getMember()==null||!event.getMember().hasPermission(Permission.MANAGE_SERVER)){
            event.reply("許可ロールの変更にはサーバー管理権限が必要です。").setEphemeral(true).queue();return;
        }
        if(option==null||option.getAsRole().isPublicRole()){
            event.reply("@everyone以外のロールを指定してください。").setEphemeral(true).queue();return;
        }
        String roleId=option.getAsRole().getId();
        event.deferReply(true).queue(hook->{try{access.update(roleId,"accept".equals(sub));say(hook,"<@&"+roleId+"> をAI利用の許可対象"+("accept".equals(sub)?"に追加しました。":"から削除しました。")+"\n複数の登録ロールがある場合は、どれか1つを持つ人が利用できます。");}
            catch(Exception e){say(hook,"保存できなかったため、許可設定を変更していません。");}});
    }
    private void models(SlashCommandInteractionEvent event,InteractionHook hook) throws Exception {
        synchronized(session) {
        String user=guild;var a=session.ready(user);var models=transport.models(a.accessToken);
        String name=option(event,"name","");
        if(name.isEmpty()){
            say(hook,"現在のモデル: "+(a.model.isEmpty()?"未選択":a.model)+"\n"+models.stream().map(m->m.name()+" : "+m.slug()).collect(java.util.stream.Collectors.joining("\n"))+"\n/gvd model name:<slug> で選択してください。");return;
        }
        if(models.stream().noneMatch(m->m.slug().equals(name))){say(hook,"このアカウントで利用できるモデルのslugを指定してください。");return;}
        a.model=name;accounts.put(accounts.activeKey(user),a,false);say(hook,"応答モデルを "+name+" に保存しました。");
        }
    }
    private static String option(SlashCommandInteractionEvent e,String name,String fallback){var o=e.getOption(name);return o==null?fallback:o.getAsString();}
    private static void say(InteractionHook hook,String text){
        var edit=hook.editOriginal(text.length()<=1900?text:"一覧・結果を添付しました。").setAllowedMentions(List.of());
        if(text.length()>1900)edit.setFiles(FileUpload.fromData(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),"result.txt"));
        edit.queue(ignored->{},error->{});
    }
    @Override public void onShutdown(ShutdownEvent event){worker.shutdownNow();}
}
