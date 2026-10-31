package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreadReplySenderTest {
    @TempDir Path directory;
    final Message source=mock(Message.class);
    final MessageChannelUnion channel=mock(MessageChannelUnion.class);
    final MessageCreateAction summary=mock(MessageCreateAction.class,RETURNS_SELF);
    final MessageCreateAction post=mock(MessageCreateAction.class,RETURNS_SELF);
    final CharacterSpec character=new CharacterSpec("案内人","説明役");
    final List<ResponseSettings.CharacterRole> roles=List.of(new ResponseSettings.CharacterRole(character,"456789012345678901"));

    ThreadReplySenderTest() {
        when(source.addReaction(any(net.dv8tion.jda.api.entities.emoji.Emoji.class))).thenReturn(mock(net.dv8tion.jda.api.requests.RestAction.class));
        when(source.getChannel()).thenReturn(channel);when(channel.getId()).thenReturn("123456789012345678");
        var guild=mock(Guild.class);when(source.getGuild()).thenReturn(guild);when(guild.getId()).thenReturn("234567890123456789");
        when(source.reply(anyString())).thenReturn(summary);when(channel.sendMessage(anyString())).thenReturn(post);
        var first=mock(Message.class);when(first.getId()).thenReturn("345678901234567890");
        var second=mock(Message.class);when(second.getId()).thenReturn("345678901234567891");
        when(post.complete()).thenReturn(first,second);
    }

    CharacterReply reply(String text,List<Long> references) {
        return new CharacterReply("権限設定を確認したい。",List.of(character),List.of(
                new CharacterReply.Post("案内人","確認します。",List.of()),
                new CharacterReply.Post("案内人",text,references)));
    }

    @Test void summaryAloneRepliesToUserAndPostsFollowInOrderWithSelfRoleAndReference() throws Exception {
        var saved=new ChatHistory.History();var history=new ChatHistory(directory);
        ThreadReplySender.send(source,reply("次の説明。",List.of(1L)),1,roles,saved,()->history.save("123",saved));
        var order=inOrder(source,summary,channel,post);
        order.verify(source).reply("発言サマリ：権限設定を確認したい。");
        order.verify(summary).complete();
        order.verify(channel).sendMessage("1：案内人 <@&456789012345678901>\n確認します。");
        order.verify(post).complete();
        order.verify(channel).sendMessage(contains("2：案内人 <@&456789012345678901>\n[>>1](https://discord.com/channels/"));
        order.verify(post).complete();
        verify(source,never()).addReaction(any(net.dv8tion.jda.api.entities.emoji.Emoji.class));
        verify(post,never()).setMessageReference(anyString());
        verify(summary).mentionRepliedUser(false);verify(summary).setSuppressedNotifications(true);
        verify(post,never()).setSuppressedNotifications(anyBoolean());
        verify(post,times(2)).setAllowedMentions(List.of());
        assertEquals(saved.postMessages,history.load("123").postMessages);
    }

    @Test void multipleReferencesKeepAllLinksWithoutDiscordReply() throws Exception {
        var saved=new ChatHistory.History();saved.postMessages.put(3L,"333456789012345678");saved.postMessages.put(4L,"444456789012345678");
        ThreadReplySender.send(source,reply("説明。",List.of(3L,4L)),5,roles,saved,()->{});
        verify(post,never()).setMessageReference(anyString());
        verify(channel).sendMessage(argThat((String s)->s.contains("[>>3]")&&s.contains("[>>4]")&&s.contains("444456789012345678")));
    }

    @Test void legacyNumbersWithoutMessageIdsRemainPlainReferences() throws Exception {
        ThreadReplySender.send(source,reply("説明。",List.of(3L)),5,roles,new ChatHistory.History(),()->{});
        verify(post,never()).setMessageReference(anyString());verify(channel).sendMessage(contains(">>3 \n"));
    }

    @Test void longPostKeepsNumberAndRoleInMessageAndBodyInAttachment() throws Exception {
        ThreadReplySender.send(source,reply("長".repeat(8000),List.of()),1,roles,new ChatHistory.History(),()->{});
        verify(channel).sendMessage("2：案内人 <@&456789012345678901>\n本文を添付しました。");
        verify(post).addFiles(any(net.dv8tion.jda.api.utils.FileUpload.class));
    }

    @Test void sendFailureKeepsOnlySuccessfulPostIdsAndStops() throws Exception {
        var sent=mock(Message.class);when(sent.getId()).thenReturn("345678901234567890");
        when(post.complete()).thenReturn(sent).thenThrow(new IllegalStateException());
        var saved=new ChatHistory.History();var history=new ChatHistory(directory);
        assertThrows(IllegalStateException.class,()->ThreadReplySender.send(source,reply("説明。",List.of()),1,roles,saved,()->history.save("123",saved)));
        assertEquals(Map.of(1L,"345678901234567890"),history.load("123").postMessages);
        verify(post,times(2)).complete();
        verify(source,never()).addReaction(any(net.dv8tion.jda.api.entities.emoji.Emoji.class));
    }

    @Test void saveFailureDoesNotMarkCompletion() {
        assertThrows(java.io.IOException.class,()->ThreadReplySender.send(source,reply("説明。",List.of()),1,roles,new ChatHistory.History(),()->{throw new java.io.IOException();}));
        verify(source,never()).addReaction(any(net.dv8tion.jda.api.entities.emoji.Emoji.class));
    }

    @Test void existingHistoryWithoutMessageMapStillLoads() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("123.json"),"{\"turns\":[],\"ids\":[],\"nextPost\":42}");
        var saved=new ChatHistory(directory).load("123");assertEquals(42,saved.nextPost);assertTrue(saved.postMessages.isEmpty());
    }
}
