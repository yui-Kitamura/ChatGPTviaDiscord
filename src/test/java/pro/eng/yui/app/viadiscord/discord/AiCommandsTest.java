package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.concurrent.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.eng.yui.app.viadiscord.config.AiConfig;
import pro.eng.yui.app.viadiscord.support.FailureDiagnosticsTest;

class AiCommandsTest {

    @TempDir
    Path directory;
    static final String GUILD = "234567890123456789", ROLE = "1565364891676713192";

    @SuppressWarnings("unchecked")
    SlashCommandInteractionEvent event(String sub, Member member, InteractionHook hook) {
        var event = mock(SlashCommandInteractionEvent.class);
        var guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD);
        when(event.getGuild()).thenReturn(guild);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getName()).thenReturn("gvd");
        when(event.getSubcommandName()).thenReturn(sub);
        when(event.getMember()).thenReturn(member);
        var user = member.getUser();
        when(event.getUser()).thenReturn(user);
        var defer = mock(ReplyCallbackAction.class, RETURNS_SELF);
        when(event.deferReply(true)).thenReturn(defer);
        doAnswer(
            i -> {
                ((Consumer<InteractionHook>) i.getArgument(0)).accept(hook);
                return null;
            }
        ).when(defer).queue(any(Consumer.class), any(Consumer.class));
        var reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
        when(event.reply(anyString())).thenReturn(reply);
        return event;
    }

    @SuppressWarnings("unchecked")
    InteractionHook hook() {
        var hook = mock(InteractionHook.class);
        var edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        return hook;
    }

    AiCommands commands(AiAccess access) {
        return new AiCommands(
            GUILD,
            new AiConfig(true, "", 1455, Duration.ofSeconds(30)),
            access,
            null,
            null,
            null
        );
    }

    private void visitorChannelOption(SlashCommandInteractionEvent event, String channelId) {
        var channel = mock(net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion.class);
        when(channel.getId()).thenReturn(channelId);
        var guild = event.getGuild();
        when(channel.getGuild()).thenReturn(guild);
        when(channel.getType()).thenReturn(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT);
        var option = mock(OptionMapping.class);
        when(option.getAsChannel()).thenReturn(channel);
        when(event.getOption("ch")).thenReturn(option);
    }

    @Test
    void visitorChannelRequiresManagerAndPersistsOnlyValidGuildChannels() throws Exception {
        var access = new AiAccess(directory, "");
        var hook = hook();
        var member = VisitorAccessTest.visitor();
        var event = event("visitorch", member, hook);
        var channel = mock(net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion.class);
        var guild = event.getGuild();
        var userId = event.getUser().getId();
        when(channel.getId()).thenReturn(ChannelControlsTest.CHANNEL);
        when(channel.getGuild()).thenReturn(guild);
        when(channel.getType()).thenReturn(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT);
        var option = mock(OptionMapping.class);
        when(option.getAsChannel()).thenReturn(channel);
        when(event.getOption("ch")).thenReturn(option);
        when(guild.getMemberById(userId)).thenReturn(member);
        var listener = commands(access);
        try {
            listener.onSlashCommandInteraction(event);
            verify(event, never()).deferReply(true);
            when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
            when(channel.getType()).thenReturn(net.dv8tion.jda.api.entities.channel.ChannelType.CATEGORY);
            listener.onSlashCommandInteraction(event);

            verify(event, never()).deferReply(true);
            when(channel.getType()).thenReturn(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT);
            listener.onSlashCommandInteraction(event);
            verify(hook).editOriginal(contains("をvisitorチャンネルに追加"));
            assertTrue(
                java.nio.file.Files.readString(directory.resolve("ai-visitor.properties")).contains(
                    ChannelControlsTest.CHANNEL
                )
            );
            when(channel.getId()).thenReturn(ChannelControlsTest.THREAD);
            listener.onSlashCommandInteraction(event);
            var reloaded = new AiAccess(directory, "").visitors;
            assertTrue(reloaded.isChannel(ChannelControlsTest.CHANNEL));
            assertTrue(reloaded.isChannel(ChannelControlsTest.THREAD));
            java.nio.file.Files.delete(directory.resolve("ai-visitor.properties"));
            java.nio.file.Files.createDirectory(directory.resolve("ai-visitor.properties"));
            listener.onSlashCommandInteraction(event);
            verify(hook).editOriginal(contains("保存できなかった"));
        } finally {
            listener.onShutdown(null);
        }
    }

    @Test
    void deletingVisitorChannelRequiresManagerAndRechecksPermission() throws Exception {
        var access = new AiAccess(directory, "");
        access.visitors.setChannel(ChannelControlsTest.CHANNEL);
        access.visitors.setChannel(ChannelControlsTest.THREAD);
        var hook = hook();
        var member = VisitorAccessTest.visitor();
        var event = event("visitorch", member, hook);
        visitorChannelOption(event, ChannelControlsTest.CHANNEL);
        var guild = event.getGuild();
        var userId = event.getUser().getId();
        var delete = mock(OptionMapping.class);
        when(delete.getAsBoolean()).thenReturn(true);
        when(event.getOption("delete")).thenReturn(delete);
        var listener = commands(access);
        try {
            listener.onSlashCommandInteraction(event);
            verify(event, never()).deferReply(true);
            assertTrue(access.visitors.isChannel(ChannelControlsTest.CHANNEL));
            when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
            // Member cache no longer contains this actor.
            listener.onSlashCommandInteraction(event);
            verify(hook).editOriginal(contains("サーバー管理権限がなくなった"));

            assertTrue(access.visitors.isChannel(ChannelControlsTest.CHANNEL));
            when(guild.getMemberById(userId)).thenReturn(member);
            listener.onSlashCommandInteraction(event);
            verify(hook).editOriginal(contains("指定を解除しました"));
            var reloaded = new AiAccess(directory, "").visitors;
            assertFalse(reloaded.isChannel(ChannelControlsTest.CHANNEL));
            assertTrue(reloaded.isChannel(ChannelControlsTest.THREAD));
            // Repeated clear also succeeds.
            listener.onSlashCommandInteraction(event);
            verify(hook, times(2)).editOriginal(contains("指定を解除しました"));
        } finally {
            listener.onShutdown(null);
        }
    }

    @Test
    void visitorChannelAlwaysRequiresAnExplicitTargetIncludingDelete() throws Exception {
        var access = new AiAccess(directory, "");
        access.visitors.setChannel(ChannelControlsTest.CHANNEL);
        var member = VisitorAccessTest.visitor();
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        var event = event("visitorch", member, hook());
        var listener = commands(access);
        try {
            listener.onSlashCommandInteraction(event);
            verify(event).reply(contains("追加・解除する ch"));
            var delete = mock(OptionMapping.class);
            when(delete.getAsBoolean()).thenReturn(false);
            when(event.getOption("delete")).thenReturn(delete);
            listener.onSlashCommandInteraction(event);
            verify(event, times(2)).reply(contains("追加・解除する ch"));
            when(delete.getAsBoolean()).thenReturn(true);
            listener.onSlashCommandInteraction(event);
            verify(event, times(3)).reply(contains("追加・解除する ch"));
            verify(event, never()).deferReply(true);
            assertTrue(new AiAccess(directory, "").visitors.isChannel(ChannelControlsTest.CHANNEL));
        } finally {
            listener.onShutdown(null);
        }
    }

    @Test
    void onlyServerManagersCanAddAndRemoveAcceptedRoles() throws Exception {
        var access = new AiAccess(directory, "");
        var hook = hook();
        var member = AiAccessTest.member(false);
        var listener = commands(access);
        var event = event("accept", member, hook);
        var role = mock(Role.class);
        when(role.getId()).thenReturn(ROLE);
        var option = mock(OptionMapping.class);
        when(option.getAsRole()).thenReturn(role);
        when(event.getOption("role")).thenReturn(option);
        listener.onSlashCommandInteraction(event);
        assertTrue(access.roleIds().isEmpty());
        verify(event, never()).deferReply(true);
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);

        listener.onSlashCommandInteraction(event);
        assertEquals(Set.of(ROLE), access.roleIds());
        when(event.getSubcommandName()).thenReturn("reject");

        listener.onSlashCommandInteraction(event);

        assertTrue(access.roleIds().isEmpty());
        assertTrue(new AiAccess(directory, "").roleIds().isEmpty());
    }

    @Test
    void failedRoleSaveLogsInteractionAndRetainsPreviousAccess() throws Exception {
        var access = new AiAccess(directory, "");
        java.nio.file.Files.createDirectory(directory.resolve("ai-role.txt"));
        var hook = hook();
        var member = AiAccessTest.member(false);
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        var event = event("accept", member, hook);
        when(event.getId()).thenReturn("1566000639157473370");
        var role = mock(Role.class);
        when(role.getId()).thenReturn(ROLE);
        var option = mock(OptionMapping.class);
        when(option.getAsRole()).thenReturn(role);
        when(event.getOption("role")).thenReturn(option);
        var listener = commands(access);
        try {
            String log = FailureDiagnosticsTest.capture(() -> listener.onSlashCommandInteraction(event));
            assertTrue(log.contains("operation=ACCESS_SAVE source_id=1566000639157473370"));
            assertTrue(access.roleIds().isEmpty());
            verify(hook).editOriginal(contains("保存できなかった"));
        } finally {
            listener.onShutdown(null);
        }
    }

    @Test
    void normalAuthorizedMembersCannotReplaceSharedChatGptConnection() throws Exception {
        var access = new AiAccess(directory, ROLE);
        var member = AiAccessTest.member(false, ROLE);
        var event = event("auth", member, hook());
        commands(access).onSlashCommandInteraction(event);
        verify(event, never()).deferReply(true);
        verify(event).reply(contains("サーバー管理権限"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptWithoutRoleListsAllCurrentlyEligibleHumanMembers() throws Exception {
        var access = new AiAccess(directory, ROLE);
        var hook = hook();
        var event = event("accept", AiAccessTest.member(false), hook);
        var accepted = AiAccessTest.member(false, ROLE);
        when(accepted.getId()).thenReturn("123456789012345678");
        when(accepted.getEffectiveName()).thenReturn("eligible");
        var excluded = AiAccessTest.member(false);
        var bot = AiAccessTest.member(true, ROLE);
        var task = mock(Task.class, RETURNS_SELF);
        when(event.getGuild().loadMembers()).thenReturn(task);
        doAnswer(
            i -> {
                ((Consumer<List<Member>>) i.getArgument(0)).accept(List.of(excluded, bot, accepted));
                return task;
            }
        ).when(task).onSuccess(any());
        commands(access).onSlashCommandInteraction(event);
        verify(event.getGuild()).loadMembers();
        verify(hook).editOriginal(
            argThat((String text) -> text.contains("eligible") && text.contains("123456789012345678"))
        );
    }
}
