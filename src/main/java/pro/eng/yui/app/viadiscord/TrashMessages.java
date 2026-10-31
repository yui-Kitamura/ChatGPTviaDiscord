package pro.eng.yui.app.viadiscord;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.*;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;

/** REST history sweep, independent of the AI/limit workers and Gateway reaction cache. */
final class TrashMessages extends ListenerAdapter implements AutoCloseable {
    static final long INTERVAL_MINUTES = 30;
    private final String guildId;
    private final AiAccess access;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("gvd-trash").factory());
    private boolean started;

    TrashMessages(String guildId, AiAccess access) { this.guildId = guildId; this.access = access; }

    @Override public synchronized void onReady(ReadyEvent event) {
        if (started || worker.isShutdown()) return;
        started = true;
        worker.scheduleWithFixedDelay(() -> {
            if (!running.compareAndSet(false, true)) return;
            try { System.out.println("TRASH_SWEEP " + sweep(event.getJDA())); }
            catch (Exception e) { log(e); }
            finally { running.set(false); }
        }, INTERVAL_MINUTES, INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"gvd".equals(event.getName()) || !"trash".equals(event.getSubcommandName())) return;
        if (event.getGuild() == null || !guildId.equals(event.getGuild().getId()) || !access.permits(event.getMember())) {
            event.reply("対象サーバーのAI利用許可ロールを持つユーザーのみ実行できます。")
                    .setEphemeral(true).queue();
            return;
        }
        if (!running.compareAndSet(false, true)) {
            event.reply("ゴミ箱への転送処理は実行中です。").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> {
            try {
                worker.execute(() -> {
                    String result;
                    try(var diagnostic=FailureDiagnostics.context(event.getId())) { result = sweep(event.getJDA()); }
                    catch (Exception e) { FailureDiagnostics.log("TRASH",e,event.getId()); result = "処理を完了できませんでした。対象名・Bot権限・通信を確認してください。転送済みの投稿は次回、削除を再試行します。"; }
                    finally { running.set(false); }
                    hook.editOriginal(result).setAllowedMentions(List.of()).queue(ignored -> {}, error->FailureDiagnostics.delivery("TRASH_REPLY",hook,error));
                });
            } catch (RejectedExecutionException e) {
                FailureDiagnostics.log("TRASH_QUEUE",e,event.getId());
                running.set(false);
                hook.editOriginal("Botは停止中です。後で再実行してください。").queue(ignored->{},error->FailureDiagnostics.delivery("TRASH_REPLY",hook,error));
            }
        }, error -> { running.set(false); FailureDiagnostics.log("TRASH_ACK",error,event.getId()); });
    }

    String sweep(JDA jda) {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) return refusal("TRASH_GUILD_MISSING","対象サーバーが見つかりません。");
        var channels = guild.getTextChannelsByName("チラシの裏", false);
        if (channels.size() != 1) return refusal("TRASH_SOURCE_COUNT","「チラシの裏」が存在しないか、同名チャンネルが複数あります。処理を中止しました。");
        TextChannel source = channels.getFirst();
        var threads = new LinkedHashMap<String, ThreadChannel>();
        guild.retrieveActiveThreads().complete().stream()
                .filter(t -> t.getParentChannel().getId().equals(source.getId()))
                .forEach(t -> threads.put(t.getId(), t));
        for (var t : source.retrieveArchivedPublicThreadChannels()) threads.put(t.getId(), t);
        for (var t : source.retrieveArchivedPrivateJoinedThreadChannels()) threads.put(t.getId(), t);
        var matches = threads.values().stream().filter(t -> "ゴミ箱".equals(t.getName())).toList();
        if (matches.size() != 1) return refusal("TRASH_TARGET_COUNT","「チラシの裏」内の「ゴミ箱」が存在しないか、同名スレッドが複数あります。処理を中止しました。");
        ThreadChannel target = matches.getFirst();
        var self = guild.getSelfMember();
        if (!self.hasPermission(source, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE)
                || !self.hasPermission(target, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_SEND_IN_THREADS))
            return refusal("TRASH_PERMISSION","Botに閲覧・履歴・元投稿削除・スレッド送信の権限が必要です。処理を中止しました。");
        if (target.isLocked()) return refusal("TRASH_LOCKED","「ゴミ箱」がロックされています。処理を中止しました。");

        // Recover from a successful forward followed by failed deletion, including across restarts.
        Set<String> forwarded = new HashSet<>();
        for (Message copy : target.getIterableHistory()) {
            var ref = copy.getMessageReference();
            if (copy.getAuthor().getId().equals(jda.getSelfUser().getId()) && ref != null
                    && ref.getType() == MessageReference.MessageReferenceType.FORWARD
                    && source.getId().equals(ref.getChannelId())) forwarded.add(ref.getMessageId());
        }
        int moved = 0, failed = 0;
        for (Message message : source.getIterableHistory()) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                if (message.getId().equals(target.getId()) || !approved(guild, message)) continue;
                if (target.isArchived()) target.getManager().setArchived(false).complete();
                move(message, target, forwarded);
                moved++;
            } catch (Exception e) { failed++; FailureDiagnostics.log("TRASH_MOVE",e,message.getId()); }
        }
        return "ゴミ箱への転送・元投稿削除: " + moved + "件、失敗: " + failed + "件。";
    }

    boolean approved(Guild guild, Message message) {
        if (message.getType() != MessageType.DEFAULT && message.getType() != MessageType.INLINE_REPLY) return false;
        if (message.getReactions().stream().noneMatch(r -> r.getEmoji().equals(Emoji.fromUnicode("✅")))) return false;
        for (var type : MessageReaction.ReactionType.values()) {
            for (User user : message.retrieveReactionUsers(Emoji.fromUnicode("✅"), type)) {
                if (user.isBot()) continue;
                try {
                    if (access.permits(guild.retrieveMemberById(user.getId()).useCache(false).complete())) return true;
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MEMBER) throw e;
                }
            }
        }
        return false;
    }

    static void move(Message message, ThreadChannel target, Set<String> forwarded) {
        if (!forwarded.contains(message.getId())) {
            message.forwardTo(target).setAllowedMentions(List.of()).setSuppressedNotifications(true).complete();
            forwarded.add(message.getId());
        }
        message.delete().complete();
    }

    private String refusal(String operation,String text) {FailureDiagnostics.log(operation,null,guildId);return text;}

    private static void log(Throwable error) {
        FailureDiagnostics.log("TRASH",error);
    }
    @Override public void onShutdown(ShutdownEvent event) { close(); }
    @Override public void close() { worker.shutdownNow(); }
}
