package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CharacterReplyTest {
    private final ResponseContext context = new ResponseContext(ResponseMode.THREAD, List.of(), List.of());
    static final String REPLY = """
            {"summary":"説明を求めている。","characters":[{"name":"案内人","persona":"落ち着いた説明役"}],
             "posts":[{"character":"案内人","text":"説明します。","references":[4]}]}
            """;

    @Test void validatesStructuredSpeakersAndUsesBotAssignedNumbering() throws Exception {
        var result = CharacterReply.parse(REPLY, context, 5);
        assertEquals(new CharacterSpec("案内人", "落ち着いた説明役"), result.characters().getFirst());
        assertEquals("説明を求めている。",result.summary());
        assertEquals(List.of(4L),result.posts().getFirst().references());
    }

    @Test void rejectsMalformedUnknownSpeakersAndFutureReferencesBeforeRoleCreation() {
        for (String invalid : List.of("ordinary answer", REPLY + "{}", REPLY.replace("説明を求めている。", ""), REPLY.replace("summary", "missing"), REPLY.replace("[4]", "[5]"),
                REPLY.replace("[4]", "[-1]"), REPLY.replace("[4]", "[4.1]"),
                REPLY.replace("\"character\":\"案内人\"", "\"character\":\"unknown\""))) {
            assertThrows(Exception.class, () -> CharacterReply.parse(invalid, context, 5));
        }
    }

    @Test void storedPersonasAndMentionedRolesReachTheGeneratorAndRemainStable() throws Exception {
        var spec = new CharacterSpec("案内人", "保存済みの話し方");
        var known = new ResponseSettings.CharacterRole(spec, "123456789012345678");
        var context = new ResponseContext(ResponseMode.THREAD, List.of(known), List.of(known));
        assertEquals(spec, CharacterReply.parse(REPLY, context, 5).characters().getFirst());
        String instructions = CharacterReply.instructions(context, 5);
        assertTrue(instructions.contains("保存済みの話し方"));assertTrue(instructions.contains("今回メンションされたキャラ名: [\"案内人\"]"));
        assertTrue(instructions.contains("先頭レス番号: 5"));
        assertFalse(CharacterReply.instructions(new ResponseContext(ResponseMode.QA, List.of(known), List.of(known)), 5).contains("JSON オブジェクトだけ"));
    }
}
