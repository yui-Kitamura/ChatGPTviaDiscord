package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;

/** Sends in order so references can address posts from this very response. */
final class ThreadReplySender {
    @FunctionalInterface interface Save { void run() throws IOException; }

    static void send(Message source, CharacterReply reply, long firstPost,
                     List<ResponseSettings.CharacterRole> roles, ChatHistory.History saved, Save save) throws IOException {
        source.reply("発言サマリ：" + reply.summary() + "\nAI による掛け合い")
                .mentionRepliedUser(false).setAllowedMentions(List.of()).setSuppressedNotifications(true)
                .failOnInvalidReply(true).complete();
        Map<String,String> roleIds=roles.stream().collect(Collectors.toMap(r->r.character().name(), ResponseSettings.CharacterRole::roleId));
        for(int i=0;i<reply.posts().size();i++) {
            var post=reply.posts().get(i);
            long number=firstPost+i;
            var text=new StringBuilder().append(number).append("：").append(post.character());
            String roleId=roleIds.get(post.character());
            if(roleId!=null)text.append(" <@&").append(roleId).append('>');
            text.append('\n');
            for(long reference:post.references()) {
                String id=saved.postMessages.get(reference);
                if(id==null)text.append(">>").append(reference);
                else text.append("[>>").append(reference).append("](https://discord.com/channels/")
                        .append(source.getGuild().getId()).append('/').append(source.getChannel().getId()).append('/').append(id).append(')');
                text.append(' ');
            }
            if(!post.references().isEmpty())text.append('\n');
            String header=text.toString();
            text.append(post.text());
            // Keep one Discord message per number even for long posts.
            boolean attachment=text.length()>2000;
            MessageCreateAction action=source.getChannel().sendMessage(attachment?header+"本文を添付しました。":text.toString());
            String target=post.references().stream().map(saved.postMessages::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if(target!=null)action.setMessageReference(target).mentionRepliedUser(false).failOnInvalidReply(false);
            action.setAllowedMentions(List.of());
            if(attachment)action.addFiles(FileUpload.fromData(post.text().getBytes(StandardCharsets.UTF_8),"post-"+number+".txt"));
            Message sent=action.complete();
            saved.postMessages.put(number,sent.getId());
            save.run();
        }
    }
}
