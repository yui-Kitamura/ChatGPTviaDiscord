package app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiConversationTest {
    @TempDir Path directory;
    static final String ROLE="1565364891676713192",USER="123456789012345678";
    GvdListenerTest event(String user,boolean allowed,String id,String content) throws Exception {
        var h=new GvdListenerTest();h.directory=directory;h.setUp();
        when(h.user.getId()).thenReturn(user);var member=AiAccessTest.member(false,allowed?new String[]{ROLE}:new String[]{});
        when(h.messageEvent.getMember()).thenReturn(member);when(h.guild.getMemberById(user)).thenReturn(member);
        when(h.message.getId()).thenReturn(id);when(h.message.getContentRaw()).thenReturn(content);
        var mentions=mock(net.dv8tion.jda.api.entities.Mentions.class);when(mentions.getRoles()).thenReturn(List.of());when(h.message.getMentions()).thenReturn(mentions);
        when(h.message.reply(anyString())).thenReturn(h.reply);return h;
    }
    void account(ChatGptAccounts accounts) throws Exception {
        var a=new ChatGptAccounts.Account();a.clientId="oaiapp_shared";a.subject="owner";a.accessToken="access";a.refreshToken="refresh";
        a.scope="chatgpt.tokens.use.direct";a.expiresAt=Instant.now().plusSeconds(3600).getEpochSecond();a.model="chosen";
        accounts.put(ChatGptAccounts.key(ChannelControlsTest.GUILD,"default"),a,true);
    }
    @Test void twoDiscordUsersUseSharedAccountAndConversationSurvivesReopen() throws Exception {
        var input=new AtomicReference<List<Map<String,String>>>();var calls=new AtomicInteger();
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,String>> turns){assertEquals("access",token);assertEquals("chosen",model);input.set(List.copyOf(turns));calls.incrementAndGet();return "answer";}
        };
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var access=new AiAccess(directory,ROLE);var history=new ChatHistory(directory.resolve("history"));
            var first=event(USER,true,"1","first");var done=new CountDownLatch(1);doAnswer(i->{done.countDown();return null;}).when(first.reply).queue(any(),any());
            try(var ai=new AiConversation(access,first.controls,session,transport,new ResponseSettings(directory.resolve("response.properties")),history,"guide")){
                new GvdListener(first.controls,ai).onMessageReceived(first.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
            }
            var second=event("234567890123456789",true,"2","second");var done2=new CountDownLatch(1);doAnswer(i->{done2.countDown();return null;}).when(second.reply).queue(any(),any());
            try(var ai=new AiConversation(access,second.controls,session,transport,new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")){
                new GvdListener(second.controls,ai).onMessageReceived(second.messageEvent);assertTrue(done2.await(5,TimeUnit.SECONDS));
            }
            assertEquals(2,calls.get());assertEquals(List.of("first","answer","second"),input.get().stream().map(t->t.get("content")).toList());
            verify(second.reply).mentionRepliedUser(false);verify(second.reply).setSuppressedNotifications(true);verify(second.reply).setAllowedMentions(argThat(Collection::isEmpty));
        }
    }
    @Test void unauthorizedMemberNeverReadsBodyOrCallsProvider() throws Exception {
        var transport=mock(OpenAiTransport.class);
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,new OpenAiTransport(Duration.ofSeconds(3)),0)){
            var h=event(USER,false,"1","private");clearInvocations(h.message);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")){
                new GvdListener(h.controls,ai).onMessageReceived(h.messageEvent);
                verify(h.message,never()).getContentRaw();verifyNoInteractions(transport);verify(h.message,never()).reply(anyString());
            }
        }
    }
}
