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
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    ModeCommandListener(String guildId, ResponseSettings settings, ChatGptSession session) {
        this.guildId = guildId;
        this.settings = settings;
        this.session = session;
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("gvd") || !"mode".equals(event.getSubcommandName())) return;
        if (!event.isFromGuild() || !event.getGuild().getId().equals(guildId)) {
            event.reply("このサーバーでは利用できません。").setEphemeral(true).queue();
            return;
        }
        if (event.getMember() == null || !event.getMember().hasPermission(event.getGuildChannel(), Permission.MANAGE_CHANNEL)) {
            event.reply("切替にはこの場所のチャンネル管理権限が必要です。").setEphemeral(true).queue();
            return;
        }
        var option = event.getOption("mode");
        final ResponseMode mode;
        try { mode = ResponseMode.parse(option == null ? "" : option.getAsString()); }
        catch (IllegalArgumentException e) {
            event.reply("mode は thread または qa を指定してください。").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> worker.submit(() -> {
            try {
                settings.setMode(event.getChannel().getId(), mode);
                hook.editOriginal("この場所の応答モードを " + mode.value() + " に保存しました。"
                        + "\n" + status()
                        + "\nthread では会話に応じたキャラと Discord ロールを作成・再利用し、レス番号を続けます。Bot にロールの管理権限が必要です。")
                        .setAllowedMentions(java.util.List.of()).queue();
            } catch (IOException e) {
                hook.editOriginal("設定を保存できませんでした。保存先の権限・空き容量を確認して再実行してください。")
                        .queue();
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
