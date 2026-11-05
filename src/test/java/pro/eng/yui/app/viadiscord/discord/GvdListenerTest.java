package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static pro.eng.yui.app.viadiscord.discord.ChannelControlsTest.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.eng.yui.app.viadiscord.data.local.ChannelSettings;

public class GvdListenerTest {

    public static final String BOT = "999999999999999999";
    @TempDir
    public Path directory;
    public ChannelControls controls;
    GvdListener listener;
    public MessageReceivedEvent messageEvent;

    @Test
    void forwardsSingleAndBulkDeletesEvenWhenChannelIsSkippedButRejectsOtherGuilds() throws Exception {
        var ai = mock(pro.eng.yui.app.viadiscord.core.AiConversation.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var listener = new GvdListener(controls, ai);
        controls.update(GUILD, "skipch", CHANNEL);
        var single = mock(net.dv8tion.jda.api.events.message.MessageDeleteEvent.class);
        when(single.isFromGuild()).thenReturn(true);
        when(single.getGuild()).thenReturn(guild);
        var sourceChannel = messageEvent.getChannel();
        when(single.getChannel()).thenReturn(sourceChannel);
        when(single.getMessageId()).thenReturn("deleted");
        listener.onMessageDelete(single);
        verify(ai).deleted(CHANNEL, java.util.List.of("deleted"));
        var bulk = mock(net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent.class);
        when(bulk.getGuild()).thenReturn(guild);
        var bulkChannel = mock(net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion.class);
        when(bulkChannel.getId()).thenReturn(CHANNEL);
        when(bulk.getChannel()).thenReturn(bulkChannel);
        when(bulk.getMessageIds()).thenReturn(java.util.List.of("one", "two"));
        listener.onMessageBulkDelete(bulk);
        verify(ai).deleted(CHANNEL, java.util.List.of("one", "two"));
        clearInvocations(ai);
        when(guild.getId()).thenReturn("another-guild");
        listener.onMessageDelete(single);
        listener.onMessageBulkDelete(bulk);
        verifyNoInteractions(ai);
    }
    public Message message;
    public User user;
    public MessageCreateAction reply;
    public RestAction<Void> reaction;
    public Guild guild;
    SlashCommandInteractionEvent commandEvent;
    InteractionHook commandHook;
    TextChannel logChannel;
    MessageCreateAction logPost;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        controls = new ChannelControls(GUILD, new ChannelSettings(directory.resolve("settings.properties")));
        listener = new GvdListener(controls);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD);
        user = mock(User.class);
        message = mock(Message.class);
        when(message.getType()).thenReturn(MessageType.DEFAULT);
        reply = mock(MessageCreateAction.class, RETURNS_SELF);
        reaction = mock(RestAction.class);
        when(message.reply("botが読みました")).thenReturn(reply);
        when(message.addReaction(any(Emoji.class))).thenReturn(reaction);
        when(message.removeReaction(any(Emoji.class))).thenReturn(reaction);
        when(reaction.submit()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        var channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn(CHANNEL);
        messageEvent = mock(MessageReceivedEvent.class);
        when(messageEvent.isFromGuild()).thenReturn(true);
        when(messageEvent.getGuild()).thenReturn(guild);
        when(messageEvent.getChannel()).thenReturn(channel);
        when(messageEvent.getChannelType()).thenReturn(ChannelType.TEXT);
        when(messageEvent.getMessage()).thenReturn(message);
        when(messageEvent.getAuthor()).thenReturn(user);
        var mentions = mock(net.dv8tion.jda.api.entities.Mentions.class);
        when(message.getMentions()).thenReturn(mentions);
        when(message.getContentRaw()).thenReturn("");
        var jda = mock(net.dv8tion.jda.api.JDA.class);
        var self = mock(net.dv8tion.jda.api.entities.SelfUser.class);
        when(self.getId()).thenReturn(BOT);
        when(jda.getSelfUser()).thenReturn(self);
        when(messageEvent.getJDA()).thenReturn(jda);
    }

    @Test
    void humanMessageGetsEyesAndAnUnmentionedSilentReply() {
        listener.onMessageReceived(messageEvent);
        verify(message).addReaction(argThat(emoji -> "👀".equals(emoji.getName())));
        verify(reaction).submit();
        verify(message).reply("botが読みました");
        verify(reply).mentionRepliedUser(false);
        verify(reply).setAllowedMentions(argThat(Collection::isEmpty));
        verify(reply).setSuppressedNotifications(true);
        verify(reply).failOnInvalidReply(true);
        verify(reply).queue(any(), any());
        verify(message, never()).getContentRaw();
    }

    @Test
    void readReceiptRemovesEyesAfterOutput() {
        doAnswer(
            i -> {
                i.<Consumer<Message>>getArgument(0).accept(message);
                return null;
            }
        ).when(reply).queue(any(), any());
        listener.onMessageReceived(messageEvent);
        verify(message).removeReaction(argThat(e -> "👀".equals(e.getName())));
    }

    @Test
    void skipSuppressesBothSideEffects() throws Exception {
        controls.update(GUILD, "skipch", CHANNEL);
        listener.onMessageReceived(messageEvent);
        verify(message, never()).addReaction(any(Emoji.class));
        verify(message, never()).reply(anyString());
    }

    public void mentionBot(String token) {
        var self = messageEvent.getJDA().getSelfUser();
        when(message.getMentions().getUsers()).thenReturn(java.util.List.of(self));
        when(message.getContentRaw()).thenReturn(token + " question");
    }

    public void mentionRole(String name) {
        var role = mock(net.dv8tion.jda.api.entities.Role.class);
        when(role.getName()).thenReturn(name);
        when(message.getMentions().getRoles()).thenReturn(java.util.List.of(role));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "<@999999999999999999>", "<@!999999999999999999>", "GvD: 案内人", "GvD: 地図観測班"
    })
    void mentionsOverrideSkipAndReachConversation(String mention) throws Exception {
        controls.update(GUILD, "skipch", CHANNEL);
        if (mention.startsWith("GvD:")) {
            mentionRole(mention);
        } else {
            mentionBot(mention);
        }
        Consumer<MessageReceivedEvent> conversation = mock(Consumer.class);
        new GvdListener(controls, conversation).onMessageReceived(messageEvent);
        verify(conversation).accept(messageEvent);
        // The mention does not change the persisted skip policy for later posts.

        assertFalse(controls.shouldRespond(GUILD, CHANNEL, null, false, false, MessageType.DEFAULT));
    }

    @Test
    void mentionOverridesParentAndThreadSkip() throws Exception {
        var thread = mock(net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel.class);
        var parent = mock(net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion.class);
        when(parent.getId()).thenReturn(CHANNEL);
        when(thread.getParentChannel()).thenReturn(parent);
        when(messageEvent.getChannelType()).thenReturn(ChannelType.GUILD_PUBLIC_THREAD);
        when(messageEvent.getChannel().getId()).thenReturn(THREAD);
        when(messageEvent.getChannel().asThreadChannel()).thenReturn(thread);
        controls.update(GUILD, "skipch", CHANNEL);
        mentionBot("<@" + BOT + ">");
        assertTrue(controls.shouldRespond(messageEvent));
        controls.update(GUILD, "readch", CHANNEL);
        controls.update(GUILD, "skipch", THREAD);

        assertTrue(controls.shouldRespond(messageEvent));
    }

    @Test
    void unrelatedMentionsAndImplicitReplyDoNotOverrideSkip() throws Exception {
        controls.update(GUILD, "skipch", CHANNEL);
        mentionRole("Other GvD: 案内人");
        when(message.getContentRaw()).thenReturn("@everyone @here GvD: 案内人 <@123456789012345678>");
        assertFalse(controls.shouldRespond(messageEvent));
        mentionBot("");
        when(message.getType()).thenReturn(MessageType.INLINE_REPLY);

        assertFalse(controls.shouldRespond(messageEvent));
    }

    @Test
    void mentionsDoNotBypassExcludedSources() throws Exception {
        controls.update(GUILD, "skipch", CHANNEL);
        mentionBot("<@" + BOT + ">");
        mentionRole("GvD: 案内人");
        when(user.isBot()).thenReturn(true);
        assertFalse(controls.shouldRespond(messageEvent));
        when(user.isBot()).thenReturn(false);
        when(message.isWebhookMessage()).thenReturn(true);

        assertFalse(controls.shouldRespond(messageEvent));
        when(message.isWebhookMessage()).thenReturn(false);
        when(message.getType()).thenReturn(MessageType.GUILD_MEMBER_JOIN);

        assertFalse(controls.shouldRespond(messageEvent));
        when(message.getType()).thenReturn(MessageType.DEFAULT);
        when(guild.getId()).thenReturn(OTHER);

        assertFalse(controls.shouldRespond(messageEvent));
        when(messageEvent.isFromGuild()).thenReturn(false);

        assertFalse(controls.shouldRespond(messageEvent));
    }

    @Test
    void botWebhookAndSystemMessagesNeverProduceSideEffects() {
        when(user.isBot()).thenReturn(true);
        listener.onMessageReceived(messageEvent);
        when(user.isBot()).thenReturn(false);
        when(message.isWebhookMessage()).thenReturn(true);

        listener.onMessageReceived(messageEvent);
        when(message.isWebhookMessage()).thenReturn(false);
        when(message.getType()).thenReturn(MessageType.GUILD_MEMBER_JOIN);

        listener.onMessageReceived(messageEvent);
        verify(message, never()).addReaction(any(Emoji.class));
        verify(message, never()).reply(anyString());
    }

    @Test
    void otherGuildsAndDirectMessagesAreIgnored() {
        when(guild.getId()).thenReturn(OTHER);
        listener.onMessageReceived(messageEvent);
        when(messageEvent.isFromGuild()).thenReturn(false);

        listener.onMessageReceived(messageEvent);
        verify(message, never()).addReaction(any(Emoji.class));
        verify(message, never()).reply(anyString());
    }

    @SuppressWarnings("unchecked")
    void prepareSlashCommand() {
        var event = mock(SlashCommandInteractionEvent.class);
        commandEvent = event;
        when(event.getName()).thenReturn("gvd");
        when(event.getSubcommandName()).thenReturn("skipch");
        when(event.getUser()).thenReturn(user);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        var channel = mock(GuildChannelUnion.class);
        when(channel.getGuild()).thenReturn(guild);
        when(channel.getId()).thenReturn(CHANNEL);
        when(channel.getAsMention()).thenReturn("<#" + CHANNEL + ">");
        when(channel.getType()).thenReturn(ChannelType.TEXT);
        var option = mock(OptionMapping.class);
        when(option.getAsChannel()).thenReturn(channel);
        when(event.getOption("ch")).thenReturn(option);
        var deferred = mock(ReplyCallbackAction.class);
        when(event.deferReply(true)).thenReturn(deferred);
        var hook = mock(InteractionHook.class);
        commandHook = hook;
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        doAnswer(
            invocation -> {
                Consumer<InteractionHook> success = invocation.getArgument(0);
                success.accept(hook);
                return null;
            }
        ).when(deferred).queue(any(), any());

        logChannel = mock(TextChannel.class);
        when(guild.getSystemChannel()).thenReturn(logChannel);
        logPost = mock(MessageCreateAction.class, RETURNS_SELF);
        when(logChannel.sendMessage(anyString())).thenReturn(logPost);
        doAnswer(
            invocation -> {
                Consumer<Message> success = invocation.getArgument(0);
                success.accept(message);
                return null;
            }
        ).when(logPost).queue(any(), any());
    }

    @Test
    void slashCommandsPersistAndPostNormallyInSystemChannel() throws Exception {
        prepareSlashCommand();
        listener.onSlashCommandInteraction(commandEvent);
        verify(commandEvent).deferReply(true);
        verify(logChannel).sendMessage("<#" + CHANNEL + "> ではROMります");
        verify(logPost).setAllowedMentions(argThat(Collection::isEmpty));
        verify(commandHook).editOriginal("デフォルトログチャンネルに結果を投稿しました。");
        assertTrue(new ChannelSettings(directory.resolve("settings.properties")).isSkipped(GUILD, CHANNEL, null));

        when(commandEvent.getSubcommandName()).thenReturn("readch");

        listener.onSlashCommandInteraction(commandEvent);
        assertFalse(new ChannelSettings(directory.resolve("settings.properties")).isSkipped(GUILD, CHANNEL, null));
        verify(logChannel).sendMessage("<#" + CHANNEL + "> ではROMりません");
        verify(commandEvent, never()).reply(anyString());
    }

    @Test
    void missingSystemChannelReportsSavedSettingWithoutPosting() throws Exception {
        prepareSlashCommand();
        when(guild.getSystemChannel()).thenReturn(null);
        listener.onSlashCommandInteraction(commandEvent);
        assertTrue(controlsShouldSkip());
        verify(commandHook).editOriginal(contains("未設定"));
        verifyNoInteractions(logChannel);
    }

    @Test
    void saveFailureDoesNotPostSuccessOrChangeSettings() throws Exception {
        prepareSlashCommand();
        Files.createDirectory(directory.resolve("settings.properties"));
        Files.writeString(directory.resolve("settings.properties/blocker"), "block");
        listener.onSlashCommandInteraction(commandEvent);
        assertFalse(controlsShouldSkip());
        verify(commandHook).editOriginal("保存できなかったため、設定は変更していません。");
        verifyNoInteractions(logChannel);
    }

    @Test
    void asynchronousPostFailureReportsThatSettingsWereSaved() throws Exception {
        prepareSlashCommand();
        doAnswer(
            invocation -> {
                Consumer<Throwable> failure = invocation.getArgument(1);
                failure.accept(new IllegalStateException());
                return null;
            }
        ).when(logPost).queue(any(), any());
        listener.onSlashCommandInteraction(commandEvent);
        assertTrue(controlsShouldSkip());
        verify(commandHook).editOriginal(contains("投稿に失敗しました"));
        verify(commandHook, never()).editOriginal("デフォルトログチャンネルに結果を投稿しました。");
    }

    @Test
    void synchronousPostFailureReportsThatSettingsWereSaved() throws Exception {
        prepareSlashCommand();
        when(logChannel.sendMessage(anyString())).thenThrow(new IllegalStateException());
        listener.onSlashCommandInteraction(commandEvent);
        assertTrue(controlsShouldSkip());
        verify(commandHook).editOriginal(contains("投稿に失敗しました"));
    }

    private boolean controlsShouldSkip() {
        return !controls.shouldRespond(GUILD, CHANNEL, null, false, false, MessageType.DEFAULT);
    }

    @Test
    void otherGvdSubcommandsAreLeftForTheirOwnHandlers() {
        var event = mock(SlashCommandInteractionEvent.class);
        when(event.getName()).thenReturn("gvd");
        when(event.getSubcommandName()).thenReturn("auth");
        when(event.getUser()).thenReturn(user);
        listener.onSlashCommandInteraction(event);
        verify(event, never()).reply(anyString());
        verify(event, never()).deferReply(anyBoolean());
    }
}
