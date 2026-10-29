package app.viadiscord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/** Reports only known information; checking limits never makes a paid inference request. */
final class LimitCommandListener extends ListenerAdapter {
    private final String guildId;

    LimitCommandListener(String guildId) {
        this.guildId = guildId;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!handles(event.getName(), event.getSubcommandName())) return;
        String sourceGuild = event.getGuild() == null ? null : event.getGuild().getId();
        event.reply(replyFor(sourceGuild)).setEphemeral(false).queue(
                ignored -> {},
                error -> System.err.println("LIMIT_REPLY_FAILED: Discord interaction response could not be delivered."));
    }

    static boolean handles(String command, String subcommand) {
        return "gvd".equals(command) && "limit".equals(subcommand);
    }

    String replyFor(String sourceGuild) {
        if (!guildId.equals(sourceGuild)) {
            return "このコマンドは Bot の設定対象サーバー内で利用してください。";
        }
        return """
                AI の利用枠・残量
                残量: 取得できません（この Bot の AI 接続・認証はまだ未実装です）。
                回復時刻: 不明です。
                ChatGPT アカウントの利用状況は「設定 → Usage」で確認してください。
                https://chatgpt.com/settings/usage
                この確認では AI の利用枠を消費するリクエストは送信していません。""";
    }
}
