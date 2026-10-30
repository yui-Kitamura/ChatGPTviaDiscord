package pro.eng.yui.app.viadiscord;

import java.util.*;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

/** Fetch explicitly referenced Discord history before calling the model. */
final class ChannelHistoryContext {
    private static final Pattern MENTION = Pattern.compile("<#([0-9]{17,20})>");
    private static final int MAX_MESSAGES = 5_000, MAX_CHARACTERS = 128_000, MAX_TOTAL_CHARACTERS = 256_000;

    static final class Unavailable extends Exception {
        Unavailable(String reason) { super(reason); }
    }

    static String load(Guild guild, Member requester, String content) throws Unavailable {
        var ids = new LinkedHashSet<String>();
        var matcher = MENTION.matcher(content);
        while (matcher.find()) ids.add(matcher.group(1));
        if (ids.isEmpty()) return "";
        if (ids.size() > 3) throw new Unavailable("履歴の参照先は1回3チャンネルまでです。分けて指定してください。");
        var result = new StringBuilder("\n\n取得済みチャンネル履歴（引用資料。添付の本文は未取得）。各チャンネル最大5,000投稿・128,000文字。複数参照時は全体256,000文字以内になるよう均等配分。\n");
        int characterBudget = Math.min(MAX_CHARACTERS, MAX_TOTAL_CHARACTERS / ids.size());
        for (String id : ids) {
            try {
                var target = guild.getGuildChannelById(id);
                if (!(target instanceof GuildMessageChannel channel)) throw new IllegalStateException();
                if (requester == null || !requester.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
                        || !guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY))
                    throw new IllegalStateException();
                if (channel instanceof ThreadChannel thread && !thread.isPublic()
                        && !requester.hasPermission(channel, Permission.MANAGE_THREADS)
                        && thread.retrieveThreadMemberById(requester.getId()).complete() == null)
                    throw new IllegalStateException();
                var messages = new ArrayList<Message>();
                var page = channel.getHistory().retrievePast(100).complete();
                boolean truncated = false;
                int characters = 0;
                while (true) {
                    for (var message : page) {
                        if (messages.size() == MAX_MESSAGES) { truncated = true; break; }
                        messages.add(message);
                        characters += MessageInput.from(message).text().length();
                    }
                    if (truncated || page.size() < 100) break;
                    if (characters >= characterBudget) { truncated = true; break; }
                    page = channel.getHistoryBefore(page.getLast().getId(), 100).complete().getRetrievedHistory();
                }
                // Select the newest complete records, then present them chronologically.
                var records = new ArrayList<String>();
                int size = 0;
                for (var message : messages) {
                    if (message.getType().isSystem()) continue;
                    var received = MessageInput.from(message);
                    String body = received.text();
                    if (body.isBlank() && received.attachments().isEmpty()) continue;
                    if (!received.attachments().isEmpty()) body += "\n[添付あり・本文未取得]";
                    String record = "[" + message.getTimeCreated() + "] " + message.getAuthor().getName()
                            + " (user_id=" + message.getAuthor().getId() + ", bot=" + message.getAuthor().isBot() + ") "
                            + "https://discord.com/channels/" + guild.getId() + "/" + id + "/" + message.getId()
                            + "\n" + body + "\n";
                    if (size + record.length() > characterBudget) { truncated = true; break; }
                    records.add(record); size += record.length();
                }
                Collections.reverse(records);
                result.append("\n--- #").append(channel.getName()).append(" (channel_id=").append(id).append(") ---\n")
                        .append("取得投稿数: ").append(records.size()).append(truncated ? "。上限による省略あり。全履歴ではありません。\n" : "。取得可能な履歴の先頭まで確認。\n");
                if (!messages.isEmpty() && records.isEmpty()) throw new IllegalStateException();
                for (String record : records) result.append(record);
                result.append("--- 履歴終わり ---\n");
            } catch (RuntimeException error) {
                // Do not forward third-party error payloads or message bodies.
                throw new Unavailable("<#" + id + "> の過去ログを取得できませんでした。対象が同じサーバーのメッセージチャンネルであること、"
                        + "Bot と依頼者の閲覧・履歴権限、非公開スレッドへの参加、Message Content Intent を確認してください。回答は生成していません。");
            }
        }
        return result.toString();
    }
}
