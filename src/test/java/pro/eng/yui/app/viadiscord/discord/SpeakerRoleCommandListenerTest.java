package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.eng.yui.app.viadiscord.data.local.ResponseSettings;

class SpeakerRoleCommandListenerTest {

    @TempDir
    Path directory;
    static final String GUILD = "123456789012345678", ROLE = "223456789012345678";

    SlashCommandInteractionEvent event() {
        var event = mock(SlashCommandInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getName()).thenReturn("gvd");
        when(event.getSubcommandName()).thenReturn("role");
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild().getId()).thenReturn(GUILD);
        when(event.getMember().hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        when(event.getUser().getId()).thenReturn("323456789012345678");
        when(event.getGuild().getMemberById(event.getUser().getId()).hasPermission(Permission.MANAGE_SERVER))
                .thenReturn(true);
        when(event.getOption("role").getAsRole().getGuild().getId()).thenReturn(GUILD);
        when(event.getOption("role").getAsRole().getId()).thenReturn(ROLE);
        when(event.getOption("name").getAsString()).thenReturn("owner");
        return event;
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesRegistrationAndReportsPersistenceFailure() throws Exception {
        Path file = directory.resolve("state.properties");
        var settings = new ResponseSettings(file);
        var listener = new SpeakerRoleCommandListener(GUILD, settings);
        var event = event();
        var hook = mock(InteractionHook.class, RETURNS_DEEP_STUBS);
        var defer = event.deferReply(true);
        doAnswer(i -> {
            i.<Consumer<InteractionHook>>getArgument(0).accept(hook);
            return null;
        }).when(defer).queue(any(), any());
        listener.onSlashCommandInteraction(event);
        assertEquals("owner", new ResponseSettings(file).speakerName(ROLE));
        verify(hook).editOriginal(contains("登録しました"));
        Files.delete(file);
        Files.createDirectory(file);
        when(event.getOption("name").getAsString()).thenReturn("updated");
        listener.onSlashCommandInteraction(event);
        assertEquals("owner", settings.speakerName(ROLE));
        verify(hook).editOriginal(contains("変更していません"));
    }

    @Test
    void rejectsWrongGuildMissingPermissionEveryoneAndMissingArguments() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        var listener = new SpeakerRoleCommandListener(GUILD, settings);
        var wrongGuild = event();
        when(wrongGuild.getGuild().getId()).thenReturn("other");
        var denied = event();
        when(denied.getMember().hasPermission(Permission.MANAGE_SERVER)).thenReturn(false);
        var everyone = event();
        when(everyone.getOption("role").getAsRole().isPublicRole()).thenReturn(true);
        var missing = event();
        when(missing.getOption("name")).thenReturn(null);
        for (var event : java.util.List.of(wrongGuild, denied, everyone, missing)) {
            listener.onSlashCommandInteraction(event);
            verify(event, never()).deferReply(anyBoolean());
            verify(event).reply(anyString());
        }
        assertEquals("", settings.speakerName(ROLE));
    }
}
