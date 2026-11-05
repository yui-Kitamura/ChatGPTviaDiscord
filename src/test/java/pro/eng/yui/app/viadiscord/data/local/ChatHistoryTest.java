package pro.eng.yui.app.viadiscord.data.local;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatHistoryTest {

    @TempDir
    Path directory;
    private static final String KEY = "123-abc", OTHER = "456-abc";

    private ChatHistory.History exchanges(ChatHistory history, String key) throws Exception {
        var saved = history.load(key);
        for (int i=0; i<3; i++) {
            saved.turns.add(new LinkedHashMap<>(Map.of("role", "user", "content", "question" + i)));
            saved.turns.add(new LinkedHashMap<>(Map.of("role", "assistant", "content", "answer" + i)));
            saved.ids.add("user" + i);
            saved.messageTurns.put("user" + i, i * 2);
            saved.messageTurns.put("bot" + i, i * 2 + 1);
            saved.postMessages.put((long) i + 1, "bot" + i);
            history.saveReply(key, "bot" + i, "answer" + i);
        }
        saved.nextPost = 4;
        history.save(key, saved);
        return saved;
    }

    @Test
    void removesDeletedExchangeAndDerivedTailButPreservesEarlierTurnsAndOtherChannel() throws Exception {
        var history = new ChatHistory(directory);
        exchanges(history, KEY);
        exchanges(history, OTHER);
        history.deleted("123", List.of("user1"));
        var restarted = new ChatHistory(directory);
        var saved = restarted.load(KEY);
        assertEquals(2, saved.turns.size());
        assertEquals(List.of("user0"), saved.ids);
        assertEquals(Map.of("user0", 0, "bot0", 1), saved.messageTurns);
        assertEquals(Map.of(1L, "bot0"), saved.postMessages);
        assertEquals(4, saved.nextPost);
        assertEquals("answer0", restarted.replyBody(KEY, "bot0"));
        assertNull(restarted.replyBody(KEY, "bot1"));
        assertNull(restarted.replyBody(KEY, "bot2"));
        assertEquals(6, restarted.load(OTHER).turns.size());
    }

    @Test
    void bulkDeletionOfBotOutputCleansAllAccountsAndIsIdempotent() throws Exception {
        var history = new ChatHistory(directory);
        exchanges(history, KEY);
        exchanges(history, "123-def");
        history.deleted("123", List.of("unrelated", "bot2", "bot1"));
        history.deleted("123", List.of("bot1", "bot2"));
        assertEquals(2, history.load(KEY).turns.size());
        assertEquals(2, history.load("123-def").turns.size());
    }

    @Test
    void legacyMatchingInputOrCachedReplyClearsConversation() throws Exception {
        var history = new ChatHistory(directory);
        var saved = exchanges(history, KEY);
        saved.messageTurns.clear();
        history.save(KEY, saved);
        history.deleted("123", List.of("bot1"));
        assertTrue(history.load(KEY).turns.isEmpty());
        assertNull(history.replyBody(KEY, "bot0"));
    }

    @Test
    void unrelatedDeletionPreservesSavedHistoryButRejectsOldSnapshotsAndLateReplyBodies() throws Exception {
        var history = new ChatHistory(directory);
        var saved = exchanges(history, KEY);
        var beforeFirstSave = history.load("123-def");
        history.deleted("123", List.of("unrelated"));
        assertEquals(6, history.load(KEY).turns.size());
        assertThrows(ChatHistory.Invalidated.class, () -> history.save(KEY, saved));
        assertThrows(ChatHistory.Invalidated.class, () -> history.save("123-def", beforeFirstSave));
        assertThrows(ChatHistory.Invalidated.class, () -> history.saveReply(KEY, saved, "late", "deleted"));
        assertNull(history.replyBody(KEY, "late"));
    }

    @Test
    void lateReplyCacheDoesNotOverwriteNewerConversationAndRemainsDeletable() throws Exception {
        var history = new ChatHistory(directory);
        var old = exchanges(history, KEY);
        var latest = history.load(KEY);
        latest.turns.add(new LinkedHashMap<>(Map.of("role", "user", "content", "new")));
        latest.messageTurns.put("new", 6);
        latest.ids.add("new");
        history.save(KEY, latest);
        history.saveReply(KEY, old, "late", "answer2");
        assertEquals(7, history.load(KEY).turns.size());
        history.deleted("123", List.of("late"));
        assertEquals(4, history.load(KEY).turns.size());
        assertNull(history.replyBody(KEY, "late"));
    }

    @Test
    void trimRebasesMessagePositionsAndDeletedReplyRemovesCorrectExchange() throws Exception {
        var history = new ChatHistory(directory);
        var saved = exchanges(history, KEY);
        for (int i=3; i<22; i++) {
            saved.turns.add(new LinkedHashMap<>(Map.of("role", "user", "content", "q" + i)));
            saved.turns.add(new LinkedHashMap<>(Map.of("role", "assistant", "content", "a" + i)));
            saved.messageTurns.put("user" + i, i * 2);
        }
        history.save(KEY, saved);
        assertEquals(0, saved.messageTurns.get("user2"));
        assertFalse(saved.messageTurns.containsKey("user0"));
        history.deleted("123", List.of("user3"));
        assertEquals(2, history.load(KEY).turns.size());
    }

    @Test
    void failedCleanupBlocksReadsAcrossRestartAndNextDeleteRetriesPendingIds() throws Exception {
        var history = new ChatHistory(directory);
        exchanges(history, KEY);
        Files.writeString(directory.resolve("123-def.json"), "invalid json");
        assertThrows(java.io.IOException.class, () -> history.deleted("123", List.of("user1")));
        assertThrows(java.io.IOException.class, () -> history.load(KEY));
        assertThrows(java.io.IOException.class, () -> new ChatHistory(directory).load(KEY));
        Files.delete(directory.resolve("123-def.json"));
        history.deleted("123", List.of("unrelated"));
        assertEquals(2, history.load(KEY).turns.size());
    }
}
