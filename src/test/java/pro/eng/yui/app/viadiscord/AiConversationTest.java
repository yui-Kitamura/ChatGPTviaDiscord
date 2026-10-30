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
        when(h.message.reply(anyString())).thenReturn(h.reply);
        ServerModelContextTest.documents(h.guild);return h;
    }
    void account(ChatGptAccounts accounts) throws Exception {
        var a=new ChatGptAccounts.Account();a.clientId="oaiapp_shared";a.subject="owner";a.accessToken="access";a.refreshToken="refresh";
        a.scope="chatgpt.tokens.use.direct";a.expiresAt=Instant.now().plusSeconds(3600).getEpochSecond();a.model="chosen";
        accounts.put(ChatGptAccounts.key(ChannelControlsTest.GUILD,"default"),a,true);
    }
    @Test void modeSwitchStartsAtOneAndQaDoesNotGenerate() throws Exception {
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList())).thenReturn("新しい雑談スレ","気軽に話しましょう。","新しい雑談スレ","気軽に話しましょう。");
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
                verify(channel).sendMessage("## 新しい雑談スレ\n>>1 気軽に話しましょう。");
                var restarted=history.load(key);
                assertEquals(2,restarted.nextPost);assertEquals(Map.of(1L,"987654321098765432"),restarted.postMessages);
                assertEquals("## 新しい雑談スレ\n>>1 気軽に話しましょう。",restarted.turns.get(1).get("content"));
                assertEquals(ResponseMode.THREAD,settings.mode(channel.getId()));
                assertEquals("この場所の応答モードを qa に保存しました。",ai.switchMode(h.guild,channel,ResponseMode.QA).get(5,TimeUnit.SECONDS));
                assertEquals(ResponseMode.QA,settings.mode(channel.getId()));
                verify(transport,times(2)).respond(anyString(),anyString(),anyString(),anyList());
                // Re-entering thread resets the number again, including across a persisted history reload.
                ai.switchMode(h.guild,channel,ResponseMode.THREAD).get(5,TimeUnit.SECONDS);
                assertEquals(2,history.load(key).nextPost);
                verify(channel,times(2)).sendMessage("## 新しい雑談スレ\n>>1 気軽に話しましょう。");
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
    @org.junit.jupiter.params.provider.ValueSource(strings={"success","overloaded","blank","long"})
    void specifiedTitleIsUsedForAiOpeningAndRetainedOnFailure(String outcome) throws Exception {
        var transport=mock(OpenAiTransport.class);
        var response=when(transport.respond(anyString(),anyString(),anyString(),anyList()));
        switch(outcome) {
            case "overloaded" -> response.thenThrow(new ChatGptException("server_is_overloaded",200));
            case "blank" -> response.thenReturn(" ");
            case "long" -> response.thenReturn("a".repeat(1501));
            default -> response.thenReturn("指定された話題を話しましょう。");
        }
        var h=event(USER,true,"source","question");var channel=h.messageEvent.getChannel();
        var action=mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class,RETURNS_SELF);
        when(channel.sendMessage(anyString())).thenReturn(action);
        var sent=mock(Message.class);when(sent.getId()).thenReturn("987654321098765432");when(action.complete()).thenReturn(sent);
        var history=new ChatHistory(directory.resolve("history"));
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)) {
            account(accounts);
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,
                    new ResponseSettings(directory.resolve("mode.properties")),history,"guide")) {
                String result=ai.switchMode(h.guild,channel,ResponseMode.THREAD,"指定したスレタイ").get(5,TimeUnit.SECONDS);
                String opening=outcome.equals("success")?"指定された話題を話しましょう。":"新しいスレとして再開します";
                String firstPost="## 指定したスレタイ\n>>1 "+opening;
                verify(channel).sendMessage(firstPost);
                verify(transport,times(1)).respond(anyString(),anyString(),contains(">>1 本文"),
                        argThat(turns->turns.size()==1&&turns.getFirst().get("content").toString().contains("指定したスレタイ")));
                String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var saved=history.load(key);assertEquals(2,saved.nextPost);assertEquals(Map.of(1L,sent.getId()),saved.postMessages);
                assertEquals(firstPost,saved.turns.get(1).get("content"));assertTrue(result.contains("リセットしました"));
            }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"overloaded","io","blank","long","opening"})
    void failedThreadTitleRestartsWithFixedFirstPost(String failure) throws Exception {
        var transport=mock(OpenAiTransport.class);
        var response=when(transport.respond(anyString(),anyString(),anyString(),anyList()));
        switch(failure) {
            case "overloaded" -> response.thenThrow(new ChatGptException("server_is_overloaded",200));
            case "io" -> response.thenThrow(new java.io.IOException("secret upstream"));
            case "blank" -> response.thenReturn(" ");
            case "opening" -> response.thenReturn("自動生成されたスレタイ").thenThrow(new ChatGptException("server_is_overloaded",200));
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
                assertTrue(result.contains("固定文を >>1 に投稿し、安価をリセットしました"));
                assertFalse(result.contains("secret"));assertFalse(result.contains("スレ開始は完了していません"));
                String expectedPost="## "+(failure.equals("opening")?"自動生成されたスレタイ":"新しいスレ")+"\n>>1 新しいスレとして再開します";
                verify(channel).sendMessage(expectedPost);
                verify(action).setAllowedMentions(List.of());
                verify(transport,times(failure.equals("opening")?2:1)).respond(anyString(),anyString(),anyString(),anyList());
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
        when(transport.respond(anyString(),anyString(),anyString(),anyList())).thenThrow(new java.io.IOException("secret upstream"));
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
                verify(h.messageEvent.getChannel()).sendMessage("## 新しいスレ\n>>1 新しいスレとして再開します");
                assertFalse(result.contains("リセットしました"));
            }
        }
    }
    @Test void threadCreatesAndReusesRolesWithPersistentNumbersAndMentionContext() throws Exception {
        var calls=new AtomicInteger();var prompt=new AtomicReference<String>();
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns){
                prompt.set(instructions);int n=calls.incrementAndGet();
                assertTrue(ChatGptAccounts.JSON.valueToTree(turns).toString().contains("data:image/png;base64,AQID"));
                return CharacterReplyTest.REPLY.replace("[4]",n==1?"[]":"[1]");
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
                }
                verify(h.message).reply(startsWith("発言サマリ：説明を求めている。"));
                verify(h.messageEvent.getChannel()).sendMessage(contains(n+"：案内人 <@&456789012345678901>"));
                verify(postAction,never()).setMessageReference(anyString());
                if(n==2)verify(h.messageEvent.getChannel()).sendMessage(contains("[>>1](https://discord.com/channels/"));
                assertEquals(1,settings.characters().size());
            }
            assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(completed.get()!=2)Thread.sleep(10);});
            assertEquals(1,created.get());assertEquals(2,calls.get());
            assertTrue(prompt.get().contains("server background"));assertTrue(prompt.get().contains("server guidance"));assertTrue(prompt.get().contains("server user profile"));
            assertTrue(prompt.get().contains("先頭レス番号: 2"));assertTrue(prompt.get().contains("今回メンションされたキャラ名: [\"案内人\"]"));
            String key=ChannelControlsTest.CHANNEL+"-"+UUID.nameUUIDFromBytes("oaiapp_shared:owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var saved=new ChatHistory(directory.resolve("history")).load(key);
            assertEquals(3,saved.nextPost);assertEquals(4,saved.turns.size());
            assertTrue(saved.turns.get(1).get("content").toString().contains("\"characters\""));
        }
    }

    @Test void failedRoleCreationDoesNotCommitAnswerOrAdvanceNumbers() throws Exception {
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns){return CharacterReplyTest.REPLY.replace("[4]","[]");}
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
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns){assertEquals("access",token);assertEquals("chosen",model);input.set(List.copyOf(turns));calls.incrementAndGet();return "answer";}
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"agents","aboutme"})
    void missingServerDocumentNeverCallsModelOrSavesHistory(String channel) throws Exception {
        var transport=mock(OpenAiTransport.class);
        try(var accounts=new ChatGptAccounts(directory.resolve("auth"));var session=new ChatGptSession(accounts,transport,0)){
            account(accounts);var h=event(USER,true,"1","question");
            when(h.guild.getTextChannelsByName(channel,false)).thenReturn(List.of());
            var done=new CountDownLatch(1);doAnswer(i->{done.countDown();return null;}).when(h.reply).queue(any(),any());
            try(var ai=new AiConversation(new AiAccess(directory,ROLE),h.controls,session,transport,new ResponseSettings(directory.resolve("response.properties")),new ChatHistory(directory.resolve("history")),"guide")){
                ai.accept(h.messageEvent);assertTrue(done.await(5,TimeUnit.SECONDS));
            }
            verify(transport,never()).respond(anyString(),anyString(),anyString(),anyList());
            verify(h.message).reply(contains("#"+channel));
            assertFalse(java.nio.file.Files.exists(directory.resolve("history")));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void mentionedChannelHistoryReachesModelWithoutPersistingRawTranscript(boolean withAttachment) throws Exception {
        var captured=new AtomicReference<List<Map<String,Object>>>();
        var transport=new OpenAiTransport(Duration.ofSeconds(3)){
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns){
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
            verify(transport,never()).respond(anyString(),anyString(),anyString(),anyList());
            verify(h.message).reply(contains("過去ログを取得できません"));
            assertEquals(0,completed.get());
            assertFalse(java.nio.file.Files.exists(directory.resolve("history")));
        }
    }

    @Test void completionCounterOnlyAdvancesAfterSuccessfulAiDelivery() throws Exception {
        var transport=new OpenAiTransport(Duration.ofSeconds(3)) {
            @Override String respond(String token,String model,String instructions,List<Map<String,Object>> turns){return "answer";}
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
    @Test void qaAttachmentOnlyPostReachesProviderAndDownloadFailureDoesNot() throws Exception {
        var transport=mock(OpenAiTransport.class);
        when(transport.respond(anyString(),anyString(),anyString(),anyList())).thenReturn("answer");
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
                    ChatGptAccounts.JSON.valueToTree(turns).toString().contains("data:image/png;base64,AQID")));
        }
    }

}
