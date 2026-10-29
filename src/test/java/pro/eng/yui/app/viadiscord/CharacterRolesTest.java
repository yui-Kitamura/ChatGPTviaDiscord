package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CharacterRolesTest {
    @TempDir Path directory;

    static class FakeRoles implements CharacterRoles.RoleGateway {
        final Set<String> ids = new HashSet<>();
        final List<String> names = new ArrayList<>();
        public boolean exists(String id) { return ids.contains(id); }
        public String create(String name) {
            names.add(name);
            String id = Long.toString(123456789012345678L + names.size());
            ids.add(id);
            return id;
        }
    }

    @Test void dynamicallyProposedCharactersArePersistedAndReusedAfterRestart() throws Exception {
        Path file = directory.resolve("state.properties");
        var settings = new ResponseSettings(file);
        var gateway = new FakeRoles();
        var roles = new CharacterRoles(settings, gateway);
        assertTrue(settings.characters().isEmpty());
        var mapping = roles.ensureCharacters(List.of(new CharacterSpec("地図観測班", "地図を観察する。")));
        assertEquals(List.of("GvD: 地図観測班"), gateway.names);
        String id = mapping.getFirst().roleId();
        var restarted = new CharacterRoles(new ResponseSettings(file), gateway);
        var reused = restarted.ensureCharacters(List.of(new CharacterSpec("地図観測班", "別の性格にしよう")));
        assertEquals(id, reused.getFirst().roleId());
        assertEquals("地図を観察する。", reused.getFirst().character().persona());
        assertEquals(1, gateway.names.size());
        gateway.ids.clear();
        var recreated = restarted.ensureCharacters(List.of(new CharacterSpec("地図観測班", "地図を観察する。")));
        assertNotEquals(id, recreated.getFirst().roleId());
        assertEquals(1, new ResponseSettings(file).characters().size());
        assertEquals(recreated, new ResponseSettings(file).characters());
    }

    @Test void invalidBatchesDoNotPartiallyCreateRoles() throws Exception {
        var gateway = new FakeRoles();
        var roles = new CharacterRoles(new ResponseSettings(directory.resolve("state.properties")), gateway);
        var character = new CharacterSpec("担当ＡＩ", "司会をする。");
        assertEquals("担当AI", character.name());
        assertThrows(IllegalArgumentException.class, () -> roles.ensureCharacters(List.of(character,
                new CharacterSpec("担当AI", "違う性格。"))));
        assertThrows(IllegalArgumentException.class, () -> roles.ensureCharacters(IntStream.range(0, 9)
                .mapToObj(i -> new CharacterSpec("班" + i, "説明役")).toList()));
        assertTrue(gateway.names.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CharacterSpec("@everyone", "説明役"));
        assertThrows(IllegalArgumentException.class, () -> new CharacterSpec("班\n改行", "説明役"));
    }

    @Test void roleCreationFailureDoesNotRecordAFictitiousRole() throws Exception {
        var settings = new ResponseSettings(directory.resolve("state.properties"));
        var roles = new CharacterRoles(settings, new CharacterRoles.RoleGateway() {
            public boolean exists(String id) { return false; }
            public String create(String name) { throw new IllegalStateException("Missing Manage Roles permission"); }
        });
        assertThrows(IllegalStateException.class,
                () -> roles.ensureCharacters(List.of(new CharacterSpec("新班", "説明役"))));
        assertTrue(settings.characters().isEmpty());
    }
}
