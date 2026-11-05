package pro.eng.yui.app.viadiscord.data.local;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.eng.yui.app.viadiscord.core.ResponseContext;
import pro.eng.yui.app.viadiscord.domain.model.CharacterSpec;
import pro.eng.yui.app.viadiscord.domain.model.ResponseMode;

class ResponseSettingsTest {

    @TempDir
    Path directory;

    @Test
    void speakerRolesSurviveRestartAndFailedSaveKeepsPreviousState() throws Exception {
        Path file = directory.resolve("speakers.properties");
        var settings = new ResponseSettings(file);
        String role = "123456789012345678";
        assertEquals("", settings.speakerName(role));
        settings.setSpeakerRole("owner", role);
        assertEquals("owner", new ResponseSettings(file).speakerName(role));
        settings.setSpeakerRole("別名", role);
        assertEquals("別名", new ResponseSettings(file).speakerName(role));
        assertThrows(IllegalArgumentException.class, () -> settings.setSpeakerRole("\n", role));
        assertThrows(IllegalArgumentException.class, () -> settings.setSpeakerRole("owner", "bad"));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> settings.setSpeakerRole("owner", role));
        assertEquals("別名", settings.speakerName(role));
    }

    @Test
    void aboutBindingIsAtomicSurvivesRestartAndIsPreservedWhenOmitted() throws Exception {
        Path file = directory.resolve("speakers.properties");
        var settings = new ResponseSettings(file);
        String role = "123456789012345678", about = "223456789012345678";
        settings.setSpeakerRole("owner", role, about);
        var restarted = new ResponseSettings(file);
        assertEquals("owner", restarted.speakerName(role));
        assertEquals(about, restarted.speakerAbout(role));
        restarted.setSpeakerRole("renamed", role);
        assertEquals(about, new ResponseSettings(file).speakerAbout(role));
        assertThrows(IllegalArgumentException.class, () -> settings.setSpeakerRole("bad", role, "invalid"));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> settings.setSpeakerRole("bad", role, "323456789012345678"));
        assertEquals("owner", settings.speakerName(role));
        assertEquals(about, settings.speakerAbout(role));
    }

    @Test
    void settingsAreIndependentPerChannelAndSurviveRestart() throws Exception {
        Path file = directory.resolve("state.properties");
        var settings = new ResponseSettings(file);
        assertEquals(ResponseMode.QA, settings.mode("parent"));
        settings.setMode("parent", ResponseMode.THREAD);
        assertEquals(ResponseMode.QA, settings.mode("child-thread"));
        settings.setMode("child-thread", ResponseMode.THREAD);
        settings.setMode("parent", ResponseMode.QA);
        var restarted = new ResponseSettings(file);
        assertEquals(ResponseMode.QA, restarted.mode("parent"));
        assertEquals(ResponseMode.THREAD, restarted.mode("child-thread"));
        assertThrows(IllegalArgumentException.class, () -> ResponseMode.parse("unknown"));
    }

    @Test
    void failedSaveDoesNotClaimTheModeChanged() throws Exception {
        Path parent = directory.resolve("blocked");
        var settings = new ResponseSettings(parent.resolve("state.properties"));
        Files.writeString(parent, "a file, not a directory");
        assertThrows(IOException.class, () -> settings.setMode("channel", ResponseMode.THREAD));
        assertEquals(ResponseMode.QA, settings.mode("channel"));
    }

    @Test
    void malformedStateIsNotSilentlyReset() throws Exception {
        Path file = directory.resolve("state.properties");
        Files.writeString(file, "mode.channel=unknown\n");
        assertThrows(IllegalArgumentException.class, () -> new ResponseSettings(file));
        Files.writeString(file, "speaker.invalid=owner\n");
        assertThrows(IllegalArgumentException.class, () -> new ResponseSettings(file));
    }

    @Test
    void onlyRegisteredRoleIdsBecomeContextHintsInEitherMode() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        var character = new CharacterSpec("地図観測班", "地図を観察する。落ち着いて説明する。");
        settings.putCharacter(character, "123456789012345678");
        var qa = ResponseContext.prepare(settings, "channel", Set.of("123456789012345678", "999999999999999999"));
        assertEquals(1, qa.mentionedCharacters().size());
        assertEquals(character, qa.mentionedCharacters().getFirst().character());
        assertTrue(qa.instructions().contains("1入力に1つの通常の回答"));
        settings.setMode("channel", ResponseMode.THREAD);
        var thread = ResponseContext.prepare(settings, "channel", Set.of("999999999999999999"));
        assertTrue(thread.mentionedCharacters().isEmpty());
        assertEquals(
            List.of(character),
            thread.knownCharacters().stream().map(ResponseSettings.CharacterRole::character).toList()
        );
        assertTrue(thread.instructions().contains("動的に生成"));
    }
}
