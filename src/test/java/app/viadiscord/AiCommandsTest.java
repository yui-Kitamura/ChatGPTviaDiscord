package app.viadiscord;

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

class AiCommandsTest {
    @TempDir Path directory;
    static final String GUILD="234567890123456789",ROLE="1565364891676713192";
    @SuppressWarnings("unchecked") SlashCommandInteractionEvent event(String sub,Member member,InteractionHook hook) {
        var event=mock(SlashCommandInteractionEvent.class);var guild=mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD);when(event.getGuild()).thenReturn(guild);when(event.isFromGuild()).thenReturn(true);
        when(event.getName()).thenReturn("gvd");when(event.getSubcommandName()).thenReturn(sub);when(event.getMember()).thenReturn(member);var user=member.getUser();when(event.getUser()).thenReturn(user);
        var defer=mock(ReplyCallbackAction.class,RETURNS_SELF);when(event.deferReply(true)).thenReturn(defer);
        doAnswer(i->{((Consumer<InteractionHook>)i.getArgument(0)).accept(hook);return null;}).when(defer).queue(any(Consumer.class));
        var reply=mock(ReplyCallbackAction.class,RETURNS_SELF);when(event.reply(anyString())).thenReturn(reply);
        return event;
    }
    @SuppressWarnings("unchecked") InteractionHook hook(){var hook=mock(InteractionHook.class);var edit=mock(WebhookMessageEditAction.class,RETURNS_SELF);when(hook.editOriginal(anyString())).thenReturn(edit);return hook;}
    AiCommands commands(AiAccess access){return new AiCommands(GUILD,new AiConfig(true,"",1455,Duration.ofSeconds(30)),access,null,null,null);}
    @Test void onlyServerManagersCanAddAndRemoveAcceptedRoles() throws Exception {
        var access=new AiAccess(directory,"");var hook=hook();var member=AiAccessTest.member(false);var listener=commands(access);
        var event=event("accept",member,hook);var role=mock(Role.class);when(role.getId()).thenReturn(ROLE);
        var option=mock(OptionMapping.class);when(option.getAsRole()).thenReturn(role);when(event.getOption("role")).thenReturn(option);
        listener.onSlashCommandInteraction(event);assertTrue(access.roleIds().isEmpty());verify(event,never()).deferReply(true);
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        listener.onSlashCommandInteraction(event);assertEquals(Set.of(ROLE),access.roleIds());
        when(event.getSubcommandName()).thenReturn("reject");listener.onSlashCommandInteraction(event);assertTrue(access.roleIds().isEmpty());
        assertTrue(new AiAccess(directory,"").roleIds().isEmpty());
    }
    @Test void normalAuthorizedMembersCannotReplaceSharedChatGptConnection() throws Exception {
        var access=new AiAccess(directory,ROLE);var member=AiAccessTest.member(false,ROLE);var event=event("auth",member,hook());
        commands(access).onSlashCommandInteraction(event);verify(event,never()).deferReply(true);verify(event).reply(contains("サーバー管理権限"));
    }
    @Test @SuppressWarnings("unchecked") void acceptWithoutRoleListsAllCurrentlyEligibleHumanMembers() throws Exception {
        var access=new AiAccess(directory,ROLE);var hook=hook();var event=event("accept",AiAccessTest.member(false),hook);
        var accepted=AiAccessTest.member(false,ROLE);when(accepted.getId()).thenReturn("123456789012345678");when(accepted.getEffectiveName()).thenReturn("eligible");
        var excluded=AiAccessTest.member(false);var bot=AiAccessTest.member(true,ROLE);
        var task=mock(Task.class,RETURNS_SELF);when(event.getGuild().loadMembers()).thenReturn(task);
        doAnswer(i->{((Consumer<List<Member>>)i.getArgument(0)).accept(List.of(excluded,bot,accepted));return task;}).when(task).onSuccess(any());
        commands(access).onSlashCommandInteraction(event);
        verify(event.getGuild()).loadMembers();verify(hook).editOriginal(argThat((String text)->text.contains("eligible")&&text.contains("123456789012345678")));
    }
}
