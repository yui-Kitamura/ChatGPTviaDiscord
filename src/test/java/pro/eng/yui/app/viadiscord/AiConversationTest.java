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

@org.junit.jupiter.api.extension.ExtendWith(AttachmentHttpFixture.class)
class AiConversationTest {
    @TempDir Path directory;
    static final String ROLE="1565364891676713192",USER="123456789012345678";
    GvdListenerTest event(String user,boolean allowed,String id,String content) throws Exception {
        var h=new GvdListenerTest();h.directory=directory;h.setUp();
        when(h.user.getId()).thenReturn(user);var member=AiAccessTest.member(false,allowed?new String[]{ROLE}:new String[]{});
        when(h.messageEvent.getMember()).thenReturn(member);when(h.guild.getMemberById(user)).thenReturn(member);
        when(h.message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-10-30T18:02:03Z"));
        when(h.message.getId()).thenReturn(id);when(h.message.getContentRaw()).thenReturn(content);
        var mentions=mock(net.dv8tion.jda.api.entities.Mentions.class);when(mentions.getRoles()).thenReturn(List.of());when(h.message.getMentions()).thenReturn(mentions);
        when(h.message.reply(anyString())).thenReturn(h.reply);
        var summary=mock(Message.class);when(summary.getId()).thenReturn("876654321098765432");when(h.reply.complete()).thenReturn(summary);
        ServerModelContextTest.documents(h.guild);return h;
    }
    @Test void discordReplyReachesProviderAndSentBodyIsSaved() throws Exception {
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class))).thenReturn("answer");
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var h=event(USER,true,"new","これについて");
            when(h.message.getType()).thenReturn(net.dv8tion.jda.api.entities.MessageType.INLINE_REPLY);
            when(h.message.getChannelId()).thenReturn(ChannelControlsTest.CHANNEL);
            var reference=mock(net.dv8tion.jda.api.entities.MessageReference.class);
            when(reference.getMessageId()).thenReturn("old");when(reference.getChannelId()).thenReturn(ChannelControlsTest.CHANNEL);
            when(h.message.getMessageReference()).thenReturn(reference);when(reference.resolve()).thenThrow(new IllegalStateException("deleted"));
            var sent=mock(Message.class);when(sent.getId()).thenReturn("sent");var done=new CountDownLatch(1);
            doAnswer(i->{i.<java.util.function.Consumer<Message>>getArgument(0).accept(sent);done.countDown();return null;}).when(h.reply).queue(any(),any());
            var history=new ChatHistory(directory.resolve("history"));
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,new ResponseSettings(directory.resolve("response.properties")),history,"guide")){
                ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
            }
            verify(transport).respond(anyString(),anyString(),anyString(),argThat(turns->{
                String content=turns.stream().filter(t->"user".equals(t.get("role"))).findFirst().orElseThrow().get("content").toString();
                return content.startsWith("これについて\n")&&content.contains("\"message_id\":\"old\"")&&content.contains("unavailable");
            }),any(OpenAiTransport.Progress.class));
            String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("answer",new ChatHistory(directory.resolve("history")).replyBody(key,"sent"));
        }
    }
    void account(ChatGptAccounts accounts) throws Exception {
        var a=new ChatGptAccounts.Account();a.clientId="oaiapp_shared";a.subject="owner";a.accessToken="access";a.refreshToken="refresh";
        a.scope="chatgpt.tokens.use.direct";a.expiresAt=Instant.now().plusSeconds(3600).getEpochSecond();a.model="chosen";
        accounts.put(ChatGptAccounts.key(ChannelControlsTest.GUILD,"default"),a,true);
    }
    @Test @SuppressWarnings("unchecked") void malformedThreadReplyCanBeRetriedOnceByItsAuthorizedAuthor() throws Exception {
        var calls=new AtomicInteger();var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns,OpenAiTransport.Progress progress) {
                assertEquals(1,turns.size());
                assertEquals("user",turns.getFirst().get("role"));
                assertEquals("question\n[転送メッセージ・引用資料]\n資料",turns.getFirst().get("content"));
                assertTrue(turns.getFirst().get("timestamp_jst").toString().endsWith("+09:00 JST"));
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
                var forwarded=event(USER,true,"forward","");
                var timestamp=OffsetDateTime.now();when(h.message.getTimeCreated()).thenReturn(timestamp);
                when(forwarded.message.getTimeCreated()).thenReturn(timestamp.plusNanos(100_000_000));
                when(forwarded.message.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("資料")));
                var listener=new GvdListener(h.controls,ai);listener.onMessageReceived(h.messageEvent);listener.onMessageReceived(forwarded.messageEvent);
                assertTrue(shown.await(5,TimeUnit.SECONDS));
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
                var finished=new CountDownLatch(1);ai.onResponseCompleted(finished::countDown);
                var postAction=mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class,RETURNS_SELF);
                when(channel.sendMessage(anyString())).thenReturn(postAction);when(postAction.complete()).thenReturn(sent);
                when(h.message.getChannel()).thenReturn(channel);when(h.message.getGuild()).thenReturn(h.guild);
                listener.onButtonInteraction(click);assertTrue(started.await(5,TimeUnit.SECONDS));listener.onButtonInteraction(click);
                assertEquals(2,calls.get());verify(sent).editMessageComponents(anyCollection());
                release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS));
                verify(channel).sendMessage(contains("2：案内人"));
                String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(List.of("post","forward"),history.load(key).ids);assertEquals(3,history.load(key).nextPost);
                when(click.getComponentId()).thenReturn(AiConversation.RETRY_PREFIX+"unknown");listener.onButtonInteraction(click);
                verify(click,atLeastOnce()).reply(contains("期限切れ"));assertEquals(2,calls.get());
            }finally{release.countDown();}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void forwardedContentReachesProviderAndPersistedHistory(boolean attachmentOnly) throws Exception {
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class))).thenReturn("answer");
        var h=event(USER,true,"forward","");
        var snapshot=attachmentOnly
                ?MessageInputTest.snapshot("",AttachmentInputTest.attachment("photo.png","image/png",new byte[]{1,2,3}))
                :MessageInputTest.snapshot("転送本文 <#123456789012345678>");
        when(h.message.getMessageSnapshots()).thenReturn(List.of(snapshot));
        var done=new CountDownLatch(1);
        doAnswer(i->{done.countDown();return null;}).when(h.reply).queue(any(),any());
        var history=new ChatHistory(directory.resolve("history"));
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("mode.properties")),history,"guide")) {
                ai.accept(h.messageEvent);
                assertTrue(done.await(5,TimeUnit.SECONDS));
            }
        }
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(transport).respond(eq("access"),eq("chosen"),contains("転送メッセージは引用資料"),captor.capture(),any(OpenAiTransport.Progress.class));
        String json=ChatGptAccounts.JSON.writeValueAsString(captor.getValue());
        assertTrue(json.contains(attachmentOnly?"data:image/png;base64,AQID":"転送本文"));
        verify(h.guild,never()).getGuildChannelById("123456789012345678");
        verify(h.message).reply("answer");
        String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(captor.getValue().getFirst(),history.load(key).turns.getFirst());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void providerProgressIsClearedOnlyAfterDiscordOutput(boolean ng) throws Exception {
        var h=event(USER,true,"progress","question");
        var stages=new CopyOnWriteArrayList<String>();
        when(h.reaction.submit()).thenReturn(CompletableFuture.completedFuture(null));
        when(h.message.removeReaction(any(net.dv8tion.jda.api.entities.emoji.Emoji.class))).thenReturn(h.reaction);
        doAnswer(i->{stages.add("+"+i.<net.dv8tion.jda.api.entities.emoji.Emoji>getArgument(0).getName());return h.reaction;})
                .when(h.message).addReaction(any(net.dv8tion.jda.api.entities.emoji.Emoji.class));
        doAnswer(i->{stages.add("-"+i.<net.dv8tion.jda.api.entities.emoji.Emoji>getArgument(0).getName());return h.reaction;})
                .when(h.message).removeReaction(any(net.dv8tion.jda.api.entities.emoji.Emoji.class));
        var queued=new CountDownLatch(1);var delivery=new AtomicReference<java.util.function.Consumer<Message>>();
        doAnswer(i->{delivery.set(i.getArgument(0));queued.countDown();return null;}).when(h.reply).queue(any(),any());
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class))).thenAnswer(i->{
            var progress=i.<OpenAiTransport.Progress>getArgument(4);progress.sent();
            if(ng) {progress.failed();throw new ChatGptException("rate_limit_exceeded",429);}
            progress.receiving();return "answer";
        });
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("mode.properties")),new ChatHistory(directory.resolve("history")),"guide")) {
                ai.accept(h.messageEvent);assertTrue(queued.await(5,TimeUnit.SECONDS));
                assertEquals(List.of("+👀","+📨","-👀",ng?"+❌":"+⤵️","-📨"),stages);
                delivery.get().accept(h.message);
                assertEquals(ng?"-❌":"-⤵️",stages.getLast());
            }
        }
    }

    @Test void separateForwardAndNoteBecomeOneTurnAndBothIdsPreventReprocessing() throws Exception {
        for(boolean forwardFirst:List.of(false,true)) {
            var transport=mock(OpenAiTransport.class);
            var request=new AtomicReference<List<Map<String,Object>>>();
            when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class)))
                    .thenAnswer(i->{request.set(List.copyOf(i.getArgument(3)));return "answer";});
            var path=directory.resolve("pair-"+forwardFirst);
            try(var accounts=new ChatGptAccounts(path.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
                account(accounts);
                var note=event(USER,true,"note-"+forwardFirst,"説明して");var forward=event(USER,true,"forward-"+forwardFirst,"");
                var time=OffsetDateTime.now();when(note.message.getTimeCreated()).thenReturn(time);
                when(forward.message.getTimeCreated()).thenReturn(time.plusNanos(100_000_000));
                when(forward.message.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("資料")));
                var history=new ChatHistory(path.resolve("history"));var done=new CountDownLatch(1);
                doAnswer(i->{done.countDown();return null;}).when(note.reply).queue(any(),any());
                try(var ai=new AiConversation(new AiAccess(directory,ROLE),note.controls,session,transport,
                        new ResponseSettings(path.resolve("mode.properties")),history,"guide")) {
                    ai.accept((forwardFirst?forward:note).messageEvent);ai.accept((forwardFirst?note:forward).messageEvent);
                    assertTrue(done.await(5,TimeUnit.SECONDS));
                    assertEquals(List.of(ChatTimestamps.turn("user","説明して\n[転送メッセージ・引用資料]\n資料",time)),request.get());
                    verify(forward.message,never()).reply(anyString());
                    String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var saved=history.load(key);assertTrue(saved.ids.containsAll(List.of(note.message.getId(),forward.message.getId())));
                    assertEquals(2,saved.turns.size());
                    ai.accept(forward.messageEvent);ai.switchMode(note.guild,note.messageEvent.getChannel(),ResponseMode.QA).get(5,TimeUnit.SECONDS);
                }
                try(var ai=new AiConversation(new AiAccess(directory,ROLE),forward.controls,session,transport,
                        new ResponseSettings(path.resolve("mode.properties")),history,"guide")) {
                    ai.accept(forward.messageEvent);
                    ai.switchMode(forward.guild,forward.messageEvent.getChannel(),ResponseMode.QA).get(5,TimeUnit.SECONDS);
                }
                verify(transport,times(1)).respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class));
            }
        }
    }

    @Test void unreadableForwardDoesNotDiagnoseIntentOrCallProvider() throws Exception {
        var transport=mock(OpenAiTransport.class);var h=event(USER,true,"empty","");
        when(h.message.getMessageSnapshots()).thenReturn(List.of(MessageInputTest.snapshot("")));
        var done=new CountDownLatch(1);doAnswer(i->{done.countDown();return null;}).when(h.reply).queue(any(),any());
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("mode.properties")),new ChatHistory(directory.resolve("history")),"guide")) {
                ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
            }
        }
        verify(transport,never()).respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class));
        verify(h.message).reply(argThat((String text)->text.contains("再投稿")&&!text.contains("Intent")));
        assertFalse(java.nio.file.Files.exists(directory.resolve("history")));
    }

    @Test void modeSwitchStartsAtOneAndQaDoesNotGenerate() throws Exception {
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class))).thenReturn("新しい雑談スレ");
        var h=event(USER,true,"source","question");
        var channel=h.messageEvent.getChannel();
        var action=mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class,RETURNS_SELF);
        when(channel.sendMessage(anyString())).thenReturn(action);
        var sent=mock(Message.class);when(sent.getId()).thenReturn("987654321098765432");when(action.complete()).thenReturn(sent);
        var settings=new ResponseSettings(directory.resolve("mode.properties"));
        var history=new ChatHistory(directory.resolve("history"));
        String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var old=history.load(key);old.nextPost=42;old.postMessages.put(1L,"old-first");old.postMessages.put(41L,"old-last");
        old.turns.add(Map.of("role","user","content","以前の話題"));history.save(key,old);
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,settings,history,"guide")) {
                assertTrue(ai.switchMode(h.guild,channel,ResponseMode.THREAD).get(5,TimeUnit.SECONDS).contains(">>1"));
                verify(channel).sendMessage("## 新しい雑談スレ\n>>1");
                var restarted=history.load(key);
                assertEquals(2,restarted.nextPost);assertEquals(Map.of(1L,"987654321098765432"),restarted.postMessages);
                assertEquals("## 新しい雑談スレ\n>>1",restarted.turns.get(1).get("content"));
                assertEquals(ResponseMode.THREAD,settings.mode(channel.getId()));
                assertEquals("この場所の応答モードを qa に保存しました。",ai.switchMode(h.guild,channel,ResponseMode.QA).get(5,TimeUnit.SECONDS));
                assertEquals(ResponseMode.QA,settings.mode(channel.getId()));
                verify(transport,times(1)).respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class));
                // Re-entering thread resets the number again, including across a persisted history reload.
                ai.switchMode(h.guild,channel,ResponseMode.THREAD).get(5,TimeUnit.SECONDS);
                assertEquals(2,history.load(key).nextPost);
                verify(channel,times(2)).sendMessage("## 新しい雑談スレ\n>>1");
            }
        }
    }
    @Test void qaReadsBackTitleWithoutCallingAiOrLoadingAnAccount() throws Exception {
        var transport=mock(OpenAiTransport.class);var h=event(USER,true,"source","question");
        try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,null,transport,
                new ResponseSettings(directory.resolve("mode.properties")),new ChatHistory(directory.resolve("history")),"guide")) {
            assertEquals("この場所の応答モードを qa に保存しました。\n指定タイトル <@123>",
                    ai.switchMode(h.guild,h.messageEvent.getChannel(),ResponseMode.QA,"指定タイトル <@123>").get(5,TimeUnit.SECONDS));
            verifyNoInteractions(transport);verify(h.messageEvent.getChannel(),never()).sendMessage(anyString());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"chosen",""})
    void specifiedTitleStartsWithBareMarkerWithoutCallingAi(String model) throws Exception {
        var transport=mock(OpenAiTransport.class);
        var h=event(USER,true,"source","question");var channel=h.messageEvent.getChannel();
        var action=mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class,RETURNS_SELF);
        when(channel.sendMessage(anyString())).thenReturn(action);
        var sent=mock(Message.class);when(sent.getId()).thenReturn("987654321098765432");when(action.complete()).thenReturn(sent);
        var history=new ChatHistory(directory.resolve("history"));
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            var accountKey=ChatGptAccounts.key(ChannelControlsTest.GUILD,"default");
            var account=accounts.get(accountKey);account.model=model;accounts.put(accountKey,account,true);
            when(h.guild.getTextChannelsByName("agents",false)).thenReturn(List.of());
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("mode.properties")),history,"guide")) {
                String result=ai.switchMode(h.guild,channel,ResponseMode.THREAD,"指定したスレタイ").get(5,TimeUnit.SECONDS);
                String firstPost="## 指定したスレタイ\n>>1";
                verify(channel).sendMessage(firstPost);
                verifyNoInteractions(transport);
                String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var saved=history.load(key);assertEquals(2,saved.nextPost);assertEquals(Map.of(1L,sent.getId()),saved.postMessages);
                assertEquals(firstPost,saved.turns.get(1).get("content"));assertTrue(result.contains("リセットしました"));
            }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"overloaded","io","blank","long"})
    void failedThreadTitleRestartsWithFixedFirstPost(String failure) throws Exception {
        var transport=mock(OpenAiTransport.class);
        var response=when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class)));
        switch(failure) {
            case "overloaded" -> response.thenThrow(new ChatGptException("server_is_overloaded",200));
            case "io" -> response.thenThrow(new java.io.IOException("secret upstream"));
            case "blank" -> response.thenReturn(" ");
            default -> response.thenReturn("a".repeat(101));
        }
        var h=event(USER,true,"source","question");
        var channel=h.messageEvent.getChannel();
        var action=mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class,RETURNS_SELF);
        when(channel.sendMessage(anyString())).thenReturn(action);
        var sent=mock(Message.class);when(sent.getId()).thenReturn("987654321098765432");when(action.complete()).thenReturn(sent);
        var settings=new ResponseSettings(directory.resolve("mode.properties"));
        var history=new ChatHistory(directory.resolve("history"));
        String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var old=history.load(key);old.nextPost=42;old.postMessages.put(1L,"old-first");old.postMessages.put(41L,"old-last");
        old.turns.add(Map.of("role","user","content","以前の話題"));history.save(key,old);
        var completed=new AtomicInteger();
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,settings,history,"guide")) {
                ai.onResponseCompleted(completed::incrementAndGet);
                String result=ai.switchMode(h.guild,channel,ResponseMode.THREAD).get(5,TimeUnit.SECONDS);
                assertTrue(result.contains("「新しいスレ」で開始し、安価をリセットしました"));
                assertFalse(result.contains("secret"));assertFalse(result.contains("スレ開始は完了していません"));
                String expectedPost="## 新しいスレ\n>>1";
                verify(channel).sendMessage(expectedPost);
                verify(action).setAllowedMentions(List.of());
                verify(transport,times(1)).respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class));
                var restarted=new ChatHistory(directory.resolve("history")).load(key);
                assertEquals(2,restarted.nextPost);assertEquals(Map.of(1L,"987654321098765432"),restarted.postMessages);
                assertEquals(2,restarted.turns.size());
                assertEquals(expectedPost,restarted.turns.get(1).get("content"));
                assertEquals(ResponseMode.THREAD,settings.mode(channel.getId()));assertEquals(1,completed.get());
            }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void failedFallbackSendOrSaveDoesNotResetExistingReferencesOrClaimSuccess(boolean failSave) throws Exception {
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class))).thenThrow(new java.io.IOException("secret upstream"));
        var h=event(USER,true,"source","question");
        var action=mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class,RETURNS_SELF);
        when(h.messageEvent.getChannel().sendMessage(anyString())).thenReturn(action);
        var settings=new ResponseSettings(directory.resolve("mode.properties"));
        var history=new ChatHistory(directory.resolve("history"));
        String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var old=history.load(key);old.nextPost=42;old.postMessages.put(1L,"old-first");history.save(key,old);
        if(failSave) {
            var sent=mock(Message.class);when(sent.getId()).thenReturn("987654321098765432");
            when(action.complete()).thenAnswer(i->{
                java.nio.file.Files.move(directory.resolve("history"),directory.resolve("history-backup"));
                java.nio.file.Files.writeString(directory.resolve("history"),"block history writes");
                return sent;
            });
        } else when(action.complete()).thenThrow(new IllegalStateException("secret discord"));
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,settings,history,"guide")) {
                String result=ai.switchMode(h.guild,h.messageEvent.getChannel(),ResponseMode.THREAD).get(5,TimeUnit.SECONDS);
                assertTrue(result.contains("完了できませんでした"));assertFalse(result.contains("secret"));
                var retained=new ChatHistory(directory.resolve(failSave?"history-backup":"history")).load(key);
                assertEquals(42,retained.nextPost);assertEquals("old-first",retained.postMessages.get(1L));
                verify(h.messageEvent.getChannel()).sendMessage("## 新しいスレ\n>>1");
                assertFalse(result.contains("リセットしました"));
            }
        }
    }
    @Test void threadCreatesAndReusesRolesWithPersistentNumbersAndMentionContext() throws Exception {
        var calls=new AtomicInteger();var prompt=new AtomicReference<String>();
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns,OpenAiTransport.Progress progress){
                prompt.set(instructions);int n=calls.incrementAndGet();
                progress.sources(List.of("https://example.com/source"));
                assertTrue(ChatGptAccounts.JSON.valueToTree(turns).toString().contains("data:image/png;base64,AQID"));
                return CharacterReplyTest.REPLY.replace("[4]",n==1?"[]":"[1]")
                        .replace("説明します。",n==1?"説明します。":"説明します。 https://example.com/source");
            }
        };
        var created=new AtomicInteger();var completed=new AtomicInteger();
        var gateway=new CharacterRoles.RoleGateway(){
            public boolean exists(String id){return "456789012345678901".equals(id);}
            public String create(String name){assertEquals("GvD: 案内人",name);created.incrementAndGet();return "456789012345678901";}
        };
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var access=new AiAccess(directory,ROLE);
            for(int n=1;n<=2;n++) {
                var h=event(USER,true,"post-"+n,"question");
                if(n==1){var image=AttachmentInputTest.attachment("photo.png","image/png",new byte[]{1,2,3});when(h.message.getAttachments()).thenReturn(List.of(image));}
                var settings=new ResponseSettings(directory.resolve("response.properties"));settings.setMode(ChannelControlsTest.CHANNEL,ResponseMode.THREAD);
                if(n==2){var role=mock(net.dv8tion.jda.api.entities.Role.class);when(role.getId()).thenReturn("456789012345678901");when(h.message.getMentions().getRoles()).thenReturn(List.of(role));}
                var channel=h.messageEvent.getChannel();
                when(h.message.getChannel()).thenReturn(channel);
                when(h.message.getGuild()).thenReturn(h.guild);
                var postAction=mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class,RETURNS_SELF);
                when(h.messageEvent.getChannel().sendMessage(anyString())).thenReturn(postAction);
                var sent=mock(Message.class);when(sent.getId()).thenReturn("98765432109876543"+n);
                String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Path historyFile=directory.resolve("history").resolve(key+".json");
                var beforeSend=new AtomicLong(Long.MAX_VALUE);
                when(postAction.complete()).thenAnswer(i->{beforeSend.set(java.nio.file.Files.size(historyFile));return sent;});
                try(var ai=new AiConversation(access,h.controls,session,transport,settings,new ChatHistory(directory.resolve("history")),"guide",guild->gateway)){
                    ai.onResponseCompleted(completed::incrementAndGet);
                    ai.accept(h.messageEvent);
                    assertTimeoutPreemptively(Duration.ofSeconds(5),()->{
                        while(beforeSend.get()==Long.MAX_VALUE||java.nio.file.Files.size(historyFile)<=beforeSend.get())Thread.sleep(10);
                    });
                    int expected=n;
                    assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(completed.get()<expected)Thread.sleep(10);});
                }
                verify(h.message).reply(startsWith((2*n-1)+">> 説明を求めている。"));
                verify(h.messageEvent.getChannel()).sendMessage(contains((2*n)+"：案内人 <@&456789012345678901>"));
                if(n==1)verify(h.messageEvent.getChannel()).sendMessage("出典:\nhttps://example.com/source");
                else verify(h.messageEvent.getChannel(),never()).sendMessage("出典:\nhttps://example.com/source");
                verify(postAction,never()).setMessageReference(anyString());
                if(n==2)verify(h.messageEvent.getChannel()).sendMessage(contains("[>>1](https://discord.com/channels/"));
                assertEquals(1,settings.characters().size());
            }
            assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(completed.get()!=2)Thread.sleep(10);});
            assertEquals(1,created.get());assertEquals(2,calls.get());
            assertTrue(prompt.get().contains("server background"));assertTrue(prompt.get().contains("server guidance"));assertTrue(prompt.get().contains("server user profile"));assertTrue(prompt.get().contains("server reference material"));
            assertTrue(prompt.get().contains("Web検索ツールを利用できます"));
            assertFalse(prompt.get().contains("Web閲覧・記憶検索・外部操作ツールはありません"));
            assertTrue(prompt.get().contains("先頭レス番号: 4"));assertTrue(prompt.get().contains("今回メンションされたキャラ名: [\"案内人\"]"));
            String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var saved=new ChatHistory(directory.resolve("history")).load(key);
            assertEquals(5,saved.nextPost);assertEquals(4,saved.turns.size());
            assertTrue(saved.turns.get(1).get("content").toString().contains("\"characters\""));
        }
    }

    @Test void failedRoleCreationDoesNotCommitAnswerOrAdvanceNumbers() throws Exception {
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns,OpenAiTransport.Progress progress){return CharacterReplyTest.REPLY.replace("[4]","[]");}
        };
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var h=event(USER,true,"post","question");var settings=new ResponseSettings(directory.resolve("response.properties"));
            settings.setMode(ChannelControlsTest.CHANNEL,ResponseMode.THREAD);
            var gateway=new CharacterRoles.RoleGateway(){public boolean exists(String id){return false;}public String create(String name){throw new IllegalStateException("private details");}};
            var done=new CountDownLatch(1);var completed=new AtomicInteger();doAnswer(i->{done.countDown();return null;}).when(h.reply).queue(any(),any());
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,settings,new ChatHistory(directory.resolve("history")),"guide",guild->gateway)){
                ai.onResponseCompleted(completed::incrementAndGet);
                ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
                assertEquals(0,completed.get());
            }
            verify(h.message).reply(contains("ロール作成・保存を完了できません"));
            assertTrue(settings.characters().isEmpty());assertFalse(java.nio.file.Files.exists(directory.resolve("history")));
        }
    }
    @Test void twoDiscordUsersUseSharedAccountAndConversationSurvivesReopen() throws Exception {
        var input=new AtomicReference<List<Map<String,Object>>>();var calls=new AtomicInteger();
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns,OpenAiTransport.Progress progress){assertEquals("access",token);assertEquals("chosen",model);input.set(List.copyOf(turns));calls.incrementAndGet();return "answer";}
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
            assertEquals("2026-10-31 03:02:03 +09:00 JST",input.get().getFirst().get("timestamp_jst"));
            assertTrue(input.get().get(1).get("timestamp_jst").toString().endsWith("+09:00 JST"));
            assertEquals("2026-10-31 03:02:03 +09:00 JST",input.get().getLast().get("timestamp_jst"));
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"agents","aboutme","lib"})
    void missingServerDocumentNeverCallsModelOrSavesHistory(String channel) throws Exception {
        var transport=mock(OpenAiTransport.class);
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var h=event(USER,true,"1","question");
            when(h.guild.getTextChannelsByName(channel,false)).thenReturn(List.of());
            var done=new CountDownLatch(1);doAnswer(i->{done.countDown();return null;}).when(h.reply).queue(any(),any());
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")){
                ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
            }
            verify(transport,never()).respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class));
            verify(h.message).reply(contains("#"+channel));
            assertFalse(java.nio.file.Files.exists(directory.resolve("history")));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void mentionedChannelHistoryReachesModelWithoutPersistingRawTranscript(boolean withAttachment) throws Exception {
        var captured=new AtomicReference<List<Map<String,Object>>>();
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns,OpenAiTransport.Progress progress){
                captured.set(List.copyOf(turns));return "readback answer";
            }
        };
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);
            String content="<#"+ChannelHistoryContextTest.ID+"> の内容readback";
            var h=event(USER,true,"post",content);
            if(withAttachment){var image=AttachmentInputTest.attachment("photo.png","image/png",new byte[]{1,2,3});when(h.message.getAttachments()).thenReturn(List.of(image));}
            var source=new ChannelHistoryContextTest();
            source.setup(ChannelHistoryContextTest.post("1","source transcript",false));
            when(h.guild.getGuildChannelById(ChannelHistoryContextTest.ID)).thenReturn(source.channel);
            when(h.guild.getSelfMember()).thenReturn(source.bot);
            when(h.messageEvent.getMember().hasPermission(source.channel,net.dv8tion.jda.api.Permission.VIEW_CHANNEL,
                    net.dv8tion.jda.api.Permission.MESSAGE_HISTORY)).thenReturn(true);
            var completed=new AtomicInteger();
            var done=new CountDownLatch(1);doAnswer(i->{i.<java.util.function.Consumer<Message>>getArgument(0).accept(h.message);done.countDown();return null;}).when(h.reply).queue(any(),any());
            var history=new ChatHistory(directory.resolve("history"));
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("response.properties")),history,"guide")){
                ai.onResponseCompleted(completed::incrementAndGet);
                ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
            }
            assertTrue(captured.get().getLast().get("content").toString().contains("source transcript"));
            if(withAttachment)assertTrue(captured.get().getLast().get("content").toString().contains("data:image/png;base64,AQID"));
            String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Object persisted=history.load(key).turns.getFirst().get("content");
            assertFalse(persisted.toString().contains("source transcript"));
            if(withAttachment)assertTrue(persisted.toString().contains("data:image/png;base64,AQID"));
            else assertEquals(content,persisted);
            verify(h.message).reply("readback answer");
            assertEquals(1,completed.get());
        }
    }

    @Test void inaccessibleMentionStopsBeforeModelAndHistorySave() throws Exception {
        var transport=mock(OpenAiTransport.class);
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var h=event(USER,true,"1","<#"+ChannelHistoryContextTest.ID+"> readback");
            var completed=new AtomicInteger();
            var done=new CountDownLatch(1);doAnswer(i->{i.<java.util.function.Consumer<Message>>getArgument(0).accept(h.message);done.countDown();return null;}).when(h.reply).queue(any(),any());
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")){
                ai.onResponseCompleted(completed::incrementAndGet);
                ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
            }
            verify(transport,never()).respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class));
            verify(h.message).reply(contains("過去ログを取得できません"));
            assertEquals(0,completed.get());
            assertFalse(java.nio.file.Files.exists(directory.resolve("history")));
        }
    }

    @Test void completionCounterOnlyAdvancesAfterSuccessfulAiDelivery() throws Exception {
        var transport=new OpenAiTransport(Duration.ofSeconds(3)) {
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns,OpenAiTransport.Progress progress){return "answer";}
        };
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            for(boolean success:new boolean[]{false,true}) {
                var h=event(USER,true,"post-"+success,"question");
                var completed=new AtomicInteger();var delivered=new CountDownLatch(1);
                doAnswer(i->{
                    if(success)i.<java.util.function.Consumer<Message>>getArgument(0).accept(h.message);
                    else i.<java.util.function.Consumer<Throwable>>getArgument(1).accept(new IllegalStateException());
                    delivered.countDown();return null;
                }).when(h.reply).queue(any(),any());
                try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                        new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")) {
                    ai.onResponseCompleted(completed::incrementAndGet);
                    ai.accept(h.messageEvent);assertTrue(delivered.await(5,TimeUnit.SECONDS));
                    assertEquals(success?1:0,completed.get());
                }
            }
        }
    }
    @Test void completeImageWithDifferentMetadataSizeReachesAi() throws Exception {
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class))).thenReturn("answer");
        var h=event(USER,true,"1566086546992533535","");
        var image=AttachmentInputTest.attachment("pc.jpg","image/jpeg",new byte[242093]);
        when(image.getSize()).thenReturn(197778);when(h.message.getAttachments()).thenReturn(List.of(image));
        AttachmentHttpFixture.lengths.put(image.getUrl(),242093L);
        var delivered=new CountDownLatch(1);
        doAnswer(i->{delivered.countDown();return null;}).when(h.reply).queue(any(),any());
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")) {
                String log=FailureDiagnosticsTest.capture(()->{
                    ai.accept(h.messageEvent);
                    try{assertTrue(delivered.await(5,TimeUnit.SECONDS));}
                    catch(InterruptedException e){throw new AssertionError(e);}
                });
                assertTrue(log.contains("GVD_ATTACHMENT_SIZE_DIFFERENCE source_id=1566086546992533535"));
                assertFalse(log.contains("operation=ATTACHMENT"));verify(h.message).reply("answer");
                verify(transport).respond(eq("access"),eq("chosen"),anyString(),argThat(turns->{
                    return turns.stream().filter(turn->"user".equals(turn.get("role")))
                            .map(turn->turn.get("content")).filter(content->content instanceof List<?>)
                            .flatMap(content->((List<?>)content).stream()).filter(part->part instanceof Map<?,?>)
                            .map(part->((Map<?,?>)part).get("image_url")).filter(data->data instanceof String)
                            .anyMatch(data->{String value=(String)data;return Base64.getDecoder().decode(value.substring(value.indexOf(',')+1)).length==242093;});
                }),any(OpenAiTransport.Progress.class));
            }
        }
    }
    @Test void attachmentFailureLogsSourceAndSizesBeforeReplyWithoutCallingAi() throws Exception {
        var transport=mock(OpenAiTransport.class);
        var h=event(USER,true,"1566000639157473370","");
        var image=AttachmentInputTest.attachment("private-photo.jpg","image/jpeg",new byte[]{1,2,3});
        when(image.getSize()).thenReturn(4);AttachmentHttpFixture.lengths.put(image.getUrl(),5L);when(h.message.getAttachments()).thenReturn(List.of(image));
        var delivered=new CountDownLatch(1);
        doAnswer(i->{delivered.countDown();return null;}).when(h.reply).queue(any(),any());
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")) {
                String log=FailureDiagnosticsTest.capture(()->{
                    ai.accept(h.messageEvent);
                    try{assertTrue(delivered.await(5,TimeUnit.SECONDS));}
                    catch(InterruptedException e){throw new AssertionError(e);}
                });
                assertTrue(log.contains("operation=ATTACHMENT source_id=1566000639157473370 reason=SIZE_MISMATCH"));
                assertTrue(log.contains("declared_bytes=4 actual_bytes=3"));assertFalse(log.contains("private-photo"));
                verify(h.message).reply(contains("添付ファイルを取得できません"));
                verifyNoInteractions(transport);
            }
        }
    }
    @Test void qaAttachmentOnlyPostReachesProviderAndDownloadFailureDoesNot() throws Exception {
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList(),any(OpenAiTransport.Progress.class))).thenReturn("answer");
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            for(boolean failed:new boolean[]{false,true}) {
                var h=event(USER,true,"attachment-"+failed,"");
                var image=AttachmentInputTest.attachment("a.png","image/png",new byte[]{1,2,3});
                if(failed)when(image.getProxy().download()).thenReturn(CompletableFuture.failedFuture(new java.io.IOException("private")));
                when(h.message.getAttachments()).thenReturn(List.of(image));
                var done=new CountDownLatch(1);doAnswer(i->{done.countDown();return null;}).when(h.reply).queue(any(),any());
                try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                        new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")) {
                    ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
                }
                if(failed)verify(h.message).reply(contains("添付ファイルを取得できません"));
                else verify(h.message).reply("answer");
            }
            verify(transport,times(1)).respond(eq("access"),eq("chosen"),anyString(),argThat(turns->
                    ChatGptAccounts.JSON.valueToTree(turns).toString().contains("data:image/png;base64,AQID")),any(OpenAiTransport.Progress.class));
        }
    }

}
