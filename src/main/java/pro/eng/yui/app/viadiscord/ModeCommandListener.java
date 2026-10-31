package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

final class ModeCommandListener extends ListenerAdapter {
    private final String guildId;
    private final ResponseSettings settings;
    private final ChatGptSession session;
    private final AiConversation conversation;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    ModeCommandListener(String guildId, ResponseSettings settings, ChatGptSession session, AiConversation conversation) {
        this.guildId = guildId;
        this.settings = settings;
        this.session = session;
        this.conversation = conversation;
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("gvd") || !"mode".equals(event.getSubcommandName())) return;
        if (!event.isFromGuild() || !event.getGuild().getId().equals(guildId)) {
            event.reply("このサーバーでは利用できません。").setEphemeral(false).queue();
            return;
        }
        if (event.getMember() == null || !event.getMember().hasPermission(event.getGuildChannel(), Permission.MANAGE_CHANNEL)) {
            event.reply("切替にはこの場所のチャンネル管理権限が必要です。").setEphemeral(false).queue();
            return;
        }
        var option = event.getOption("mode");
        final ResponseMode mode;
        try { mode = ResponseMode.parse(option == null ? "" : option.getAsString()); }
        catch (IllegalArgumentException e) {
            event.reply("mode は thread または qa を指定してください。").setEphemeral(false).queue();
            return;
        }
        var titleOption = event.getOption("title");
        String title = titleOption == null ? "" : titleOption.getAsString();
        if (title.length() > 100 || title.contains("\n") || title.contains("\r") || (!title.isEmpty() && title.isBlank())) {
            event.reply("title は1行・100文字以内のスレタイを指定してください。").setEphemeral(false).queue();
            return;
        }
        event.deferReply(false).queue(hook -> {
            try {worker.submit(() -> change(event,hook,mode,title));}
            catch(java.util.concurrent.RejectedExecutionException e) {
                FailureDiagnostics.log("MODE_QUEUE",e,event.getId());
                FailureDiagnostics.edit(hook,"処理待ちが多いため切替を受け付けられませんでした。後で再実行してください。","MODE_REPLY");
            }
        },error->FailureDiagnostics.log("MODE_ACK",error,event.getId()));
    }

    private void change(SlashCommandInteractionEvent event,net.dv8tion.jda.api.interactions.InteractionHook hook,
                        ResponseMode mode,String title) {
        try(var diagnostic=FailureDiagnostics.context(event.getId())) {
            String result;
            if(conversation!=null) {
                result=conversation.switchMode(event.getGuild(),event.getChannel(),mode,title).get();
            } else {
                settings.setMode(event.getChannel().getId(),mode);
                result="この場所の応答モードを "+mode.value()+" に保存しました。";
                if(mode==ResponseMode.QA&&!title.isEmpty())result+="\n"+title;
                if(mode==ResponseMode.THREAD)result+="\nスレタイ投稿・安価リセットは未完了です。"+status();
            }
            FailureDiagnostics.edit(hook,result,"MODE_REPLY");
        } catch(IOException|java.util.concurrent.ExecutionException e) {
            FailureDiagnostics.log("MODE_SAVE",e,event.getId());
            FailureDiagnostics.edit(hook,"設定を保存できませんでした。保存先の権限・空き容量を確認して再実行してください。","MODE_REPLY");
        } catch(InterruptedException e) {
            FailureDiagnostics.log("MODE_INTERRUPTED",e,event.getId());Thread.currentThread().interrupt();
            FailureDiagnostics.edit(hook,"モード切替の完了を確認できませんでした。","MODE_REPLY");
        } catch(java.util.concurrent.RejectedExecutionException e) {
            FailureDiagnostics.log("MODE_QUEUE",e,event.getId());
            FailureDiagnostics.edit(hook,"処理待ちが多いため切替を受け付けられませんでした。後で再実行してください。","MODE_REPLY");
        } catch(RuntimeException e) {
            FailureDiagnostics.log("MODE_CHANGE",e,event.getId());
            FailureDiagnostics.edit(hook,"モード切替を完了できませんでした。接続・設定を確認して再実行してください。","MODE_REPLY");
        }
    }

    String status() {
        if (session == null) return "ChatGPT 接続は無効です。管理者が CHATGPT_ENABLED を確認してください。";
        try { return session.connectionStatus(guildId); }
        catch (IOException e) { FailureDiagnostics.log("MODE_STATUS",e);return "接続設定の状態を読み取れませんでした。管理者が /gvd auth action:status で確認してください。"; }
    }

    @Override public void onShutdown(ShutdownEvent event) { worker.shutdown(); }
}
