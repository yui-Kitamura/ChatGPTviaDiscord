package pro.eng.yui.app.viadiscord.discord;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.Message;
import pro.eng.yui.app.viadiscord.data.local.ChatHistory;
import pro.eng.yui.app.viadiscord.data.local.ResponseSettings;
import pro.eng.yui.app.viadiscord.domain.model.CharacterReply;

/**
 * Sends in order so references can address posts from this very response.
 */
public final class ThreadReplySender {

    @FunctionalInterface
    public interface Save {

        void run() throws IOException;
    }

    @FunctionalInterface
    public interface SaveReply {

        void run(String id, String body) throws IOException;
    }

    static void send(
        Message source,
        CharacterReply reply,
        long firstPost,
        List<ResponseSettings.CharacterRole> roles,
        ChatHistory.History saved,
        Save save
    ) throws IOException {
        send(
            source,
            reply,
            firstPost,
            roles,
            saved,
            save,
            (id, body) -> {
            }
        );
    }

    static void send(
        Message source,
        CharacterReply reply,
        long firstPost,
        List<ResponseSettings.CharacterRole> roles,
        ChatHistory.History saved,
        Save save,
        SaveReply saveReply
    ) throws IOException {
        send(source, reply, firstPost, roles, saved, save, saveReply, "");
    }

    public static void send(
        Message source,
        CharacterReply reply,
        long firstPost,
        List<ResponseSettings.CharacterRole> roles,
        ChatHistory.History saved,
        Save save,
        SaveReply saveReply,
        String sourceNote
    ) throws IOException {
        String summaryBody = firstPost + ">> " + reply.summary();
        Message summary = source.reply(summaryBody)
                .mentionRepliedUser(false)
                .setAllowedMentions(List.of())
                .setSuppressedNotifications(
                    true
                )
                .failOnInvalidReply(true)
                .complete();
        saved.postMessages.put(firstPost, summary.getId());
        save.run();
        saveReply.run(summary.getId(), summaryBody);
        Map<String, String> roleIds = roles.stream().collect(
            Collectors.toMap(r -> r.character().name(), ResponseSettings.CharacterRole::roleId)
        );
        for (int i=0; i<reply.posts().size(); i++) {
            var post = reply.posts().get(i);
            long number = firstPost + 1 + i;
            var text = new StringBuilder().append(number).append("：").append(post.character());
            String roleId = roleIds.get(post.character());
            if (roleId != null) {
                text.append(" <@&").append(roleId).append('>');
            }
            text.append('\n');
            for (long reference : post.references()) {
                String id = saved.postMessages.get(reference);
                if (id == null) {
                    text.append(">>").append(reference);
                } else {
                    text.append("[>>").append(reference).append("](https://discord.com/channels/").append(
                        source.getGuild().getId()
                    ).append('/').append(source.getChannel().getId()).append('/').append(id).append(')');
                }
                text.append(' ');
            }
            if (!post.references().isEmpty()) {
                text.append('\n');
            }
            text.append(post.text());
            boolean first = true;
            for (String chunk : DiscordText.split(text.toString())) {
                Message sent = source.getChannel().sendMessage(chunk).setAllowedMentions(List.of()).complete();
                if (first) {
                    saved.postMessages.put(number, sent.getId());
                    save.run();
                    first = false;
                }
                saveReply.run(sent.getId(), text.toString());
            }
        }
        for (String chunk : DiscordText.split(sourceNote)) {
            Message sent = source.getChannel().sendMessage(chunk).setAllowedMentions(List.of()).complete();
            saveReply.run(sent.getId(), sourceNote);
        }
    }
}
