package pro.eng.yui.app.viadiscord;

import java.util.List;
import java.util.concurrent.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;

/** Checks the shared connection without sending an inference request. */
final class LimitCommandListener extends ListenerAdapter {
    private final String guildId;
    private final ChatGptSession session;
    private final OpenAiTransport transport;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofVirtual().factory(), new ThreadPoolExecutor.AbortPolicy());

    LimitCommandListener(String guildId, ChatGptSession session, OpenAiTransport transport) {
        this.guildId = guildId;
        this.session = session;
        this.transport = transport;
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!handles(event.getName(), event.getSubcommandName()) || event.getUser().isBot()) return;
        if (event.getGuild() == null || !guildId.equals(event.getGuild().getId())) {
            event.reply("このコマンドは Bot の設定対象サーバー内で利用してください。")
                    .setEphemeral(false).queue();
            return;
        }
        event.deferReply(false).queue(hook -> {
            try { worker.submit(() -> say(hook, query())); }
            catch (RejectedExecutionException e) { say(hook, "照会が混み合っています。後で /gvd limit を再実行してください。"); }
        }, error -> System.err.println("LIMIT_REPLY_FAILED: Discord interaction response could not be delivered."));
    }

    static boolean handles(String command, String subcommand) {
        return "gvd".equals(command) && "limit".equals(subcommand);
    }

    String query() {
        if (session == null) return "AI の利用枠・残量\nChatGPT 接続は無効です。管理者が CHATGPT_ENABLED を確認してください。";
        try {
            synchronized (session) {
                var account = session.ready(guildId);
                transport.models(account.accessToken); // Read-only authenticated request, not an inference probe.
                var observed = account.model.isEmpty() ? null : transport.limits(account.accessToken, account.model);
                return "AI の利用枠・残量（Bot 共通の接続）\n接続照会: 成功（モデル一覧を取得）\nモデル: "
                        + (account.model.isEmpty() ? "未選択。管理者が /gvd model で選択してください。" : account.model.substring(0, Math.min(100, account.model.length())))
                        + "\n" + (observed == null ? "現在の接続・モデルで AI 応答の観測情報がありません。\n" : observed.display())
                        + "ChatGPT プラン全体の残量・回復時刻: この接続方式の公開 API では数値を確認できません。\n"
                        + "https://chatgpt.com/settings/usage\nこの照会は AI の回答生成を行いません。";
            }
        } catch (ChatGptException e) {
            e.log();
            return "利用枠の照会を完了できませんでした。\n" + e.userMessage();
        } catch (Exception e) {
            return "利用枠の照会を完了できませんでした。時間を置いて再実行してください。自動再試行はしていません。";
        }
    }

    private static void say(InteractionHook hook, String text) {
        hook.editOriginal(text).setAllowedMentions(List.of()).queue(ignored -> {},
                error -> System.err.println("LIMIT_REPLY_FAILED: Discord interaction response could not be delivered."));
    }

    @Override public void onShutdown(ShutdownEvent event) { worker.shutdownNow(); }
}
