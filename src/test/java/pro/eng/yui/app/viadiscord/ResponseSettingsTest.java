package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResponseSettingsTest {
    @TempDir Path directory;

    @Test void settingsAreIndependentPerChannelAndSurviveRestart() throws Exception {
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

    @Test void failedSaveDoesNotClaimTheModeChanged() throws Exception {
        Path parent = directory.resolve("blocked");
        var settings = new ResponseSettings(parent.resolve("state.properties"));
        Files.writeString(parent, "a file, not a directory");
        assertThrows(IOException.class, () -> settings.setMode("channel", ResponseMode.THREAD));
        assertEquals(ResponseMode.QA, settings.mode("channel"));
    }

    @Test void malformedStateIsNotSilentlyReset() throws Exception {
        Path file = directory.resolve("state.properties");
        Files.writeString(file, "mode.channel=unknown\n");
        assertThrows(IllegalArgumentException.class, () -> new ResponseSettings(file));
    }

    @Test void onlyRegisteredRoleIdsBecomeContextHintsInEitherMode() throws Exception {
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
        assertEquals(List.of(character), thread.knownCharacters().stream().map(ResponseSettings.CharacterRole::character).toList());
        assertTrue(thread.instructions().contains("動的に生成"));
    }
}
