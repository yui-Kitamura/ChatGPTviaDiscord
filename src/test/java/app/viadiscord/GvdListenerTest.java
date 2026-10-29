package app.viadiscord;

import static app.viadiscord.ChannelControlsTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
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

class GvdListenerTest {
    @TempDir Path directory;
    ChannelControls controls;
    GvdListener listener;
    MessageReceivedEvent messageEvent;
    Message message;
    User user;
    MessageCreateAction reply;
    RestAction<Void> reaction;
    Guild guild;

    @BeforeEach @SuppressWarnings("unchecked") void setUp() throws Exception {
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
        var channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn(CHANNEL);
        messageEvent = mock(MessageReceivedEvent.class);
        when(messageEvent.isFromGuild()).thenReturn(true);
        when(messageEvent.getGuild()).thenReturn(guild);
        when(messageEvent.getChannel()).thenReturn(channel);
        when(messageEvent.getChannelType()).thenReturn(ChannelType.TEXT);
        when(messageEvent.getMessage()).thenReturn(message);
        when(messageEvent.getAuthor()).thenReturn(user);
    }

    @Test void humanMessageGetsEyesAndAnUnmentionedSilentReply() {
        listener.onMessageReceived(messageEvent);
        verify(message).addReaction(argThat(emoji -> "👀".equals(emoji.getName())));
        verify(reaction).queue(any(), any());
        verify(message).reply("botが読みました");
        verify(reply).mentionRepliedUser(false);
        verify(reply).setAllowedMentions(argThat(Collection::isEmpty));
        verify(reply).setSuppressedNotifications(true);
        verify(reply).failOnInvalidReply(true);
        verify(reply).queue(any(), any());
        verify(message, never()).getContentRaw();
    }

    @Test void skipSuppressesBothSideEffects() throws Exception {
        controls.update(GUILD, "skipch", CHANNEL);
        listener.onMessageReceived(messageEvent);
        verify(message, never()).addReaction(any(Emoji.class));
        verify(message, never()).reply(anyString());
    }

    @Test void botWebhookAndSystemMessagesNeverProduceSideEffects() {
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

    @Test void otherGuildsAndDirectMessagesAreIgnored() {
        when(guild.getId()).thenReturn(OTHER);
        listener.onMessageReceived(messageEvent);
        when(messageEvent.isFromGuild()).thenReturn(false);
        listener.onMessageReceived(messageEvent);
        verify(message, never()).addReaction(any(Emoji.class));
        verify(message, never()).reply(anyString());
    }

    @Test @SuppressWarnings("unchecked") void slashCommandsPersistAndAcknowledgePrivately() throws Exception {
        var event = mock(SlashCommandInteractionEvent.class);
        when(event.getName()).thenReturn("gvd");
        when(event.getSubcommandName()).thenReturn("skipch");
        when(event.getUser()).thenReturn(user);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        var channel = mock(GuildChannelUnion.class);
        when(channel.getGuild()).thenReturn(guild);
        when(channel.getId()).thenReturn(CHANNEL);
        when(channel.getType()).thenReturn(ChannelType.TEXT);
        var option = mock(OptionMapping.class);
        when(option.getAsChannel()).thenReturn(channel);
        when(event.getOption("ch")).thenReturn(option);
        var deferred = mock(ReplyCallbackAction.class);
        when(event.deferReply(true)).thenReturn(deferred);
        var hook = mock(InteractionHook.class);
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        doAnswer(invocation -> {
            Consumer<InteractionHook> success = invocation.getArgument(0);
            success.accept(hook);
            return null;
        }).when(deferred).queue(any(), any());

        listener.onSlashCommandInteraction(event);
        verify(event).deferReply(true);
        verify(hook).editOriginal(contains("停止しました"));
        var reloaded = new ChannelSettings(directory.resolve("settings.properties"));
        assertTrue(reloaded.isSkipped(GUILD, CHANNEL, null));

        when(event.getSubcommandName()).thenReturn("readch");
        listener.onSlashCommandInteraction(event);
        assertFalse(new ChannelSettings(directory.resolve("settings.properties")).isSkipped(GUILD, CHANNEL, null));
        verify(hook).editOriginal(contains("再開しました"));
    }

    @Test void otherGvdSubcommandsAreLeftForTheirOwnHandlers() {
        var event = mock(SlashCommandInteractionEvent.class);
        when(event.getName()).thenReturn("gvd");
        when(event.getSubcommandName()).thenReturn("auth");
        when(event.getUser()).thenReturn(user);
        listener.onSlashCommandInteraction(event);
        verify(event, never()).reply(anyString());
        verify(event, never()).deferReply(anyBoolean());
    }
}
