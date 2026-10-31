package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatTimestampsTest {
    @TempDir Path directory;

    @Test void persistsJstAcrossReopenAndOnlyDecoratesProviderInput() throws Exception {
        var history = new ChatHistory(directory);
        var saved = new ChatHistory.History();
        saved.turns.add(ChatTimestamps.turn("user", "question", OffsetDateTime.parse("2026-10-30T18:02:03Z")));
        saved.turns.add(ChatTimestamps.turn("assistant", "{\"posts\":[]}", OffsetDateTime.parse("2026-10-30T18:02:10Z")));
        history.save("123", saved);
        var reopened = new ChatHistory(directory).load("123");
        var input = ChatTimestamps.input(reopened.turns);
        assertEquals("[発言日時: 2026-10-31 03:02:03 +09:00 JST]\nquestion", input.get(0).get("content"));
        assertEquals("[発言日時: 2026-10-31 03:02:10 +09:00 JST]\n{\"posts\":[]}", input.get(1).get("content"));
        assertEquals("{\"posts\":[]}", reopened.turns.get(1).get("content"));
        assertEquals(Set.of("role", "content"), input.get(0).keySet());
    }

    @Test void legacyTimeIsUnknownAndAttachmentDataSurvivesWithoutMutation() throws Exception {
        Map<String,Object> image = Map.of("type", "input_image", "image_url", "data:image/png;base64,AQID");
        var parts = List.of(Map.of("type", "input_text", "text", "look"), image);
        var old = Map.<String,Object>of("role", "user", "content", parts);
        var saved = new ChatHistory.History();
        saved.turns.add(old);
        new ChatHistory(directory).save("123", saved);
        var input = ChatTimestamps.input(new ChatHistory(directory).load("123").turns);
        var content = (List<?>) input.getFirst().get("content");
        assertEquals(Map.of("type", "input_text", "text", "[発言日時: 不明（日時未記録の履歴）]\n"), content.get(0));
        assertEquals(parts, content.subList(1, content.size()));
        assertEquals(2, parts.size());
    }
}
