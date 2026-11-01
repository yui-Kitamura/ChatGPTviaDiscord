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
    @FunctionalInterface interface SaveReply { void run(String id,String body) throws IOException; }

    static void send(Message source, CharacterReply reply, long firstPost,
                     List<ResponseSettings.CharacterRole> roles, ChatHistory.History saved, Save save) throws IOException {
        send(source,reply,firstPost,roles,saved,save,(id,body)->{});
    }
    static void send(Message source, CharacterReply reply, long firstPost,
                     List<ResponseSettings.CharacterRole> roles, ChatHistory.History saved, Save save, SaveReply saveReply) throws IOException {
        send(source,reply,firstPost,roles,saved,save,saveReply,"");
    }
    static void send(Message source, CharacterReply reply, long firstPost,
                     List<ResponseSettings.CharacterRole> roles, ChatHistory.History saved, Save save, SaveReply saveReply,String sourceNote) throws IOException {
        String summaryBody=firstPost + ">> " + reply.summary();
        Message summary=source.reply(summaryBody)
                .mentionRepliedUser(false).setAllowedMentions(List.of()).setSuppressedNotifications(true)
                .failOnInvalidReply(true).complete();
        saved.postMessages.put(firstPost,summary.getId());
        save.run();
        saveReply.run(summary.getId(),summaryBody);
        Map<String,String> roleIds=roles.stream().collect(Collectors.toMap(r->r.character().name(), ResponseSettings.CharacterRole::roleId));
        for(int i=0;i<reply.posts().size();i++) {
            var post=reply.posts().get(i);
            long number=firstPost+1+i;
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
            action.setAllowedMentions(List.of());
            if(attachment)action.addFiles(FileUpload.fromData(post.text().getBytes(StandardCharsets.UTF_8),"post-"+number+".txt"));
            Message sent=action.complete();
            saved.postMessages.put(number,sent.getId());
            save.run();
            saveReply.run(sent.getId(),text.toString());
        }
        if(!sourceNote.isEmpty()) {
            var action=source.getChannel().sendMessage(sourceNote.length()<=2000?sourceNote:"出典を添付しました。");
            action.setAllowedMentions(List.of());
            if(sourceNote.length()>2000)action.addFiles(FileUpload.fromData(sourceNote.getBytes(StandardCharsets.UTF_8),"sources.txt"));
            Message sent=action.complete();
            saveReply.run(sent.getId(),sourceNote);
        }
    }
}
