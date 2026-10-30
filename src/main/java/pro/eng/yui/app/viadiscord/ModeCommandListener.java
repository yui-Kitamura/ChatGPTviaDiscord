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
        event.deferReply(false).queue(hook -> worker.submit(() -> {
            try {
                String result;
                if (conversation != null) {
                    result = conversation.switchMode(event.getGuild(), event.getChannel(), mode).get();
                } else {
                    settings.setMode(event.getChannel().getId(), mode);
                    result = "この場所の応答モードを " + mode.value() + " に保存しました。";
                    if (mode == ResponseMode.THREAD) result += "\nスレタイ投稿・安価リセットは未完了です。" + status();
                }
                hook.editOriginal(result)
                        .setAllowedMentions(java.util.List.of()).queue();
            } catch (IOException | java.util.concurrent.ExecutionException e) {
                hook.editOriginal("設定を保存できませんでした。保存先の権限・空き容量を確認して再実行してください。")
                        .queue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                hook.editOriginal("モード切替の完了を確認できませんでした。").queue();
            } catch (java.util.concurrent.RejectedExecutionException e) {
                hook.editOriginal("処理待ちが多いため切替を受け付けられませんでした。後で再実行してください。").queue();
            }
        }));
    }

    String status() {
        if (session == null) return "ChatGPT 接続は無効です。管理者が CHATGPT_ENABLED を確認してください。";
        try { return session.connectionStatus(guildId); }
        catch (IOException e) { return "接続設定の状態を読み取れませんでした。管理者が /gvd auth action:status で確認してください。"; }
    }

    @Override public void onShutdown(ShutdownEvent event) { worker.shutdown(); }
}
