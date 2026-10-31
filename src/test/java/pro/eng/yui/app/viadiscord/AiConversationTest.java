package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
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
    @Test @SuppressWarnings("unchecked") void malformedThreadReplyCanBeRetriedOnceByItsAuthorizedAuthor() throws Exception {
        var calls=new AtomicInteger();var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,String>> turns) {
                assertEquals(List.of(Map.of("role","user","content","question")),turns);
                if(calls.incrementAndGet()==1)return "invalid";
                started.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                return CharacterReplyTest.REPLY.replace("[4]","[]");
            }
        };
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var h=event(USER,true,"post","question");var settings=new ResponseSettings(directory.resolve("response.properties"));
            settings.setMode(ChannelControlsTest.CHANNEL,ResponseMode.THREAD);
            var history=new ChatHistory(directory.resolve("history"));var sent=mock(Message.class);when(sent.getId()).thenReturn("error-reply");
            var clear=mock(MessageEditAction.class,RETURNS_SELF);when(sent.editMessageComponents(anyCollection())).thenReturn(clear);
            var shown=new CountDownLatch(1);
            doAnswer(i->{java.util.function.Consumer<Message> success=i.getArgument(0);success.accept(sent);shown.countDown();return null;}).when(h.reply).queue(any(),any());
            var gateway=new CharacterRoles.RoleGateway(){public boolean exists(String id){return true;}public String create(String name){return "456789012345678901";}};
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,settings,history,"guide",guild->gateway)){
                var listener=new GvdListener(h.controls,ai);listener.onMessageReceived(h.messageEvent);assertTrue(shown.await(5,TimeUnit.SECONDS));
                assertEquals(1,calls.get());assertTrue(settings.characters().isEmpty());assertFalse(java.nio.file.Files.exists(directory.resolve("history")));
                var row=org.mockito.ArgumentCaptor.forClass(net.dv8tion.jda.api.components.MessageTopLevelComponent.class);
                verify(h.reply).addComponents(row.capture());
                var button=(Button)((ActionRow)row.getValue()).getComponents().getFirst();assertEquals("再送",button.getLabel());
                var click=mock(ButtonInteractionEvent.class);when(click.getComponentId()).thenReturn(button.getCustomId());
                var channel=h.messageEvent.getChannel();var member=h.messageEvent.getMember();var revoked=AiAccessTest.member(false);
                when(click.isFromGuild()).thenReturn(true);when(click.getGuild()).thenReturn(h.guild);when(click.getChannel()).thenReturn(channel);
                when(click.getMessageId()).thenReturn("error-reply");when(click.getMessage()).thenReturn(sent);when(click.getUser()).thenReturn(h.user);when(click.getMember()).thenReturn(member);
                var notice=mock(ReplyCallbackAction.class,RETURNS_SELF);when(click.reply(anyString())).thenReturn(notice);
                // Other users, revoked access, changed mode, skipped channels and mismatched messages cannot consume the retry.
                var other=mock(net.dv8tion.jda.api.entities.User.class);when(other.getId()).thenReturn("other");when(click.getUser()).thenReturn(other);listener.onButtonInteraction(click);when(click.getUser()).thenReturn(h.user);
                when(click.getMember()).thenReturn(revoked);listener.onButtonInteraction(click);when(click.getMember()).thenReturn(member);
                settings.setMode(ChannelControlsTest.CHANNEL,ResponseMode.QA);listener.onButtonInteraction(click);settings.setMode(ChannelControlsTest.CHANNEL,ResponseMode.THREAD);
                h.controls.update(ChannelControlsTest.GUILD,"skipch",ChannelControlsTest.CHANNEL);listener.onButtonInteraction(click);h.controls.update(ChannelControlsTest.GUILD,"readch",ChannelControlsTest.CHANNEL);
                when(click.getMessageId()).thenReturn("other-reply");listener.onButtonInteraction(click);when(click.getMessageId()).thenReturn("error-reply");
                verify(click,never()).deferReply(anyBoolean());assertEquals(1,calls.get());
                var deferred=mock(ReplyCallbackAction.class);when(click.deferReply(true)).thenReturn(deferred);
                var hook=mock(InteractionHook.class);var edit=mock(WebhookMessageEditAction.class,RETURNS_SELF);when(hook.editOriginal(anyString())).thenReturn(edit);
                // An acknowledgement failure leaves the same button usable.
                doAnswer(i->{java.util.function.Consumer<Throwable> failure=i.getArgument(1);failure.accept(new IllegalStateException());return null;}).when(deferred).queue(any(),any());
                listener.onButtonInteraction(click);assertEquals(1,calls.get());
                doAnswer(i->{java.util.function.Consumer<InteractionHook> success=i.getArgument(0);success.accept(hook);return null;}).when(deferred).queue(any(),any());
                var finished=new CountDownLatch(1);doAnswer(i->{finished.countDown();return null;}).when(h.reply).queue(any(),any());
                listener.onButtonInteraction(click);assertTrue(started.await(5,TimeUnit.SECONDS));listener.onButtonInteraction(click);
                assertEquals(2,calls.get());verify(sent).editMessageComponents(anyCollection());
                release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS));
                verify(h.message).reply(contains("1：名無しの案内人"));
                String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(List.of("post"),history.load(key).ids);assertEquals(2,history.load(key).nextPost);
                when(click.getComponentId()).thenReturn(AiConversation.RETRY_PREFIX+"unknown");listener.onButtonInteraction(click);
                verify(click,atLeastOnce()).reply(contains("期限切れ"));assertEquals(2,calls.get());
            }finally{release.countDown();}
        }
    }
    @Test void threadCreatesAndReusesRolesWithPersistentNumbersAndMentionContext() throws Exception {
        var calls=new AtomicInteger();var prompt=new AtomicReference<String>();
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,String>> turns){
                prompt.set(instructions);int n=calls.incrementAndGet();
                return CharacterReplyTest.REPLY.replace("[4]",n==1?"[]":"[1]");
            }
        };
        var created=new AtomicInteger();
        var gateway=new CharacterRoles.RoleGateway(){
            public boolean exists(String id){return "456789012345678901".equals(id);}
            public String create(String name){assertEquals("GvD: 案内人",name);created.incrementAndGet();return "456789012345678901";}
        };
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var access=new AiAccess(directory,ROLE);
            for(int n=1;n<=2;n++) {
                var h=event(USER,true,"post-"+n,"question");
                var settings=new ResponseSettings(directory.resolve("response.properties"));settings.setMode(ChannelControlsTest.CHANNEL,ResponseMode.THREAD);
                if(n==2){var role=mock(net.dv8tion.jda.api.entities.Role.class);when(role.getId()).thenReturn("456789012345678901");when(h.message.getMentions().getRoles()).thenReturn(List.of(role));}
                var done=new CountDownLatch(1);doAnswer(i->{done.countDown();return null;}).when(h.reply).queue(any(),any());
                try(var ai=new AiConversation(access,h.controls,session,transport,settings,new ChatHistory(directory.resolve("history")),"guide",guild->gateway)){
                    ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
                }
                verify(h.message).reply(contains(n+"：名無しの案内人"));
                assertEquals(1,settings.characters().size());
            }
            assertEquals(1,created.get());assertEquals(2,calls.get());
            assertTrue(prompt.get().contains("先頭レス番号: 2"));assertTrue(prompt.get().contains("今回メンションされたキャラ名: [\"案内人\"]"));
            String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var saved=new ChatHistory(directory.resolve("history")).load(key);
            assertEquals(3,saved.nextPost);assertEquals(4,saved.turns.size());
            assertTrue(saved.turns.get(1).get("content").contains("\"characters\""));
        }
    }

    @Test void failedRoleCreationDoesNotCommitAnswerOrAdvanceNumbers() throws Exception {
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,String>> turns){return CharacterReplyTest.REPLY.replace("[4]","[]");}
        };
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var h=event(USER,true,"post","question");var settings=new ResponseSettings(directory.resolve("response.properties"));
            settings.setMode(ChannelControlsTest.CHANNEL,ResponseMode.THREAD);
            var gateway=new CharacterRoles.RoleGateway(){public boolean exists(String id){return false;}public String create(String name){throw new IllegalStateException("private details");}};
            var done=new CountDownLatch(1);doAnswer(i->{done.countDown();return null;}).when(h.reply).queue(any(),any());
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,settings,new ChatHistory(directory.resolve("history")),"guide",guild->gateway)){
                ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
            }
            verify(h.message).reply(contains("ロール作成・保存を完了できません"));
            assertTrue(settings.characters().isEmpty());assertFalse(java.nio.file.Files.exists(directory.resolve("history")));
        }
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
