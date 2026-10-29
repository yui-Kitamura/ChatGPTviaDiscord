package pro.eng.yui.app.viadiscord;

import java.util.ArrayList;
import java.util.Collections;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;

/** Read fresh server-authored instructions, without persisting them in chat history. */
final class ServerModelContext {
    private ServerModelContext() {}

    static final class Unavailable extends Exception {
        Unavailable(String channel) {
            super("#" + channel + " の文書を取得できないため、応答を止めました。チャンネルが1つだけ存在すること、"
                    + "Bot の「チャンネルを見る」「メッセージ履歴を読む」権限と Message Content Intent、"
                    + "テキスト本文があることを確認してください。各文書は500投稿・32,000文字までです。添付ファイルは読みません。");
        }
    }

    static String load(Guild guild) throws Unavailable {
        var result = new StringBuilder("\nサーバー管理文書。#readme は背景、#agents は会話の行動指針として扱う。"
                + "同梱文書と異なる内容はサーバー管理文書を優先する。応答形式や利用可能なツールの制約は維持する。\n");
        for (String name : new String[]{"readme", "agents"}) {
            try {
                var channels = guild.getTextChannelsByName(name, false);
                if (channels.size() != 1) throw new Unavailable(name);
                var channel = channels.getFirst();
                var messages = new ArrayList<Message>();
                var page = channel.getHistory().retrievePast(100).complete();
                while (true) {
                    messages.addAll(page);
                    if (messages.size() > 500) throw new Unavailable(name);
                    if (page.size() < 100) break;
                    page = channel.getHistoryBefore(page.getLast().getId(), 100).complete().getRetrievedHistory();
                }
                Collections.reverse(messages);
                var document = new StringBuilder();
                for (var message : messages) {
                    if (!message.getType().isSystem() && !message.getContentRaw().isBlank()) {
                        document.append(message.getContentRaw()).append('\n');
                    }
                    if (document.length() > 32_000) throw new Unavailable(name);
                }
                if (document.isEmpty()) throw new Unavailable(name);
                result.append("\n--- #").append(name).append(" (channel_id=").append(channel.getId()).append(") ---\n")
                        .append(document).append("--- 文書終わり ---\n");
            } catch (Unavailable e) {
                throw e;
            } catch (RuntimeException e) {
                // Third-party errors can contain message bodies; never expose them.
                throw new Unavailable(name);
            }
        }
        return result.toString();
    }
}
