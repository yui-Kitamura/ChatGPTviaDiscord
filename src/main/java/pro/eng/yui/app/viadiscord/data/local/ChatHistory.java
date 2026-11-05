package pro.eng.yui.app.viadiscord.data.local;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Bounded recent successful exchanges; raw user/assistant turns remain distinct.
 */
public final class ChatHistory {

    public static final class History {

        public List<Map<String, Object>> turns = new ArrayList<>();
        public List<String> ids = new ArrayList<>();
        public long nextPost = 1;
        public Map<Long, String> postMessages = new LinkedHashMap<>();
        public Map<String, Integer> messageTurns = new LinkedHashMap<>();
        @com.fasterxml.jackson.annotation.JsonIgnore
        private long revision;

        public History() {
        }
    }

    private final Path directory;
    private final Map<String, Long> revisions = new HashMap<>();
    private final Set<String> blockedChannels = new HashSet<>();

    public static final class Invalidated extends IOException {

        Invalidated() {
            super("Discord messages were deleted while processing this history.");
        }
    }

    public ChatHistory(Path directory) {
        this.directory = directory;
    }

    public synchronized String replyBody(String key, String messageId) throws IOException {
        requireReadable(key);
        return replies(key).get(messageId);
    }

    public synchronized void saveReply(String key, String messageId, String body) throws IOException {
        var replies = replies(key);
        replies.put(messageId, body);
        while (replies.size() > 40 || replies.values().stream().mapToInt(String::length).sum() > 256000) {
            replies.remove(replies.keySet().iterator().next());
        }
        PrivateFiles.write(replyPath(key), ChatGptAccounts.JSON.writeValueAsString(replies));
    }

    private LinkedHashMap<String, String> replies(String key) throws IOException {
        var file = replyPath(key);
        return Files.exists(file) ? ChatGptAccounts.JSON.readValue(
            Files.readString(file),
            new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {
            }
        ) : new LinkedHashMap<>();
    }

    private Path replyPath(String key) {
        return path(key).resolveSibling(key + ".replies.json");
    }

    public synchronized History load(String key) throws IOException {
        requireReadable(key);
        return read(key);
    }

    private void requireReadable(String key) throws IOException {
        path(key);
        String channelId = key.split("-", 2)[0];
        if (blockedChannels.contains(channelId) || Files.exists(pendingPath(channelId))) {
            throw new IOException("Discord history deletion has not completed.");
        }
    }

    private History read(String key) throws IOException {
        var file = path(key);
        var value = Files.exists(file) ?
            ChatGptAccounts.JSON.readValue(Files.readString(file), History.class) :
            new History();
        if (value.postMessages == null) {
            value.postMessages = new LinkedHashMap<>();
        }
        if (value.messageTurns == null) {
            value.messageTurns = new LinkedHashMap<>();
        }
        value.revision = revision(key);
        if (value.turns == null ||
            value.ids == null ||
            value.turns.size() > 40 ||
            value.nextPost < 1 ||
            value.nextPost > 9_000_000_000_000_000L) {
            throw new IOException("Invalid recent history.");
        }

        for (var turn : value.turns) {
            if (!Set.of("user", "assistant").contains(turn.get("role")) ||
                !(turn.get("content") instanceof String || turn.get("content") instanceof List<?>)) {
                throw new IOException("Invalid history item.");
            }
        }
        if (value.messageTurns.values().stream().anyMatch(index ->
            index == null || index < 0 || index >= value.turns.size())) {
            throw new IOException("Invalid Discord message history mapping.");
        }
        return value;
    }

    public synchronized void save(String key, History history) throws IOException {
        requireCurrent(key, history);
        int previousSize = history.turns.size();
        trim(history.turns);
        int removed = previousSize - history.turns.size();
        history.messageTurns.replaceAll((id, index) -> index - removed);
        history.messageTurns.values().removeIf(index -> index < 0 || index >= history.turns.size());
        while (history.ids.size() > 200) {
            history.ids.remove(0);
        }
        PrivateFiles.write(path(key), ChatGptAccounts.JSON.writeValueAsString(history));
    }

    public synchronized void requireCurrent(String key, History history) throws Invalidated {
        if (history.revision != revision(key)) {
            throw new Invalidated();
        }
    }

    private long revision(String key) {
        return revisions.getOrDefault(key.split("-", 2)[0], 0L);
    }

    public synchronized void saveReply(String key, History history, String messageId, String body)
        throws IOException {
        requireCurrent(key, history);
        var current = load(key);
        String sourceId = history.ids.isEmpty() ? "" : history.ids.getLast();
        Integer index = current.messageTurns.get(sourceId);
        if (index != null) {
            current.messageTurns.merge(messageId, index, Math::min);
            save(key, current);
            // Synchronous thread delivery saves the same snapshot after each post.
            history.messageTurns.merge(messageId, Math.max(0, history.turns.size() - 2), Math::min);
        }
        saveReply(key, messageId, body);
    }

    /**
     * Later answers may quote deleted input, so retain only the unaffected prefix.
     * Advance the revision even before the first save to invalidate in-flight answers.
     */
    public synchronized void deleted(String channelId, Collection<String> messageIds) throws IOException {
        if (!channelId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid channel ID.");
        }
        revisions.merge(channelId, 1L, Long::sum);
        if (!Files.isDirectory(directory)) {
            return;
        }

        blockedChannels.add(channelId);
        var pending = new HashSet<>(messageIds);
        if (Files.exists(pendingPath(channelId))) {
            pending.addAll(ChatGptAccounts.JSON.readValue(
                Files.readString(pendingPath(channelId)),
                new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() {
                }
            ));
        }
        PrivateFiles.write(pendingPath(channelId), ChatGptAccounts.JSON.writeValueAsString(pending));

        IOException failure = null;
        var visited = new HashSet<String>();
        try (var files = Files.newDirectoryStream(directory, channelId + "-*.json")) {
            for (var file : files) {
                String name = file.getFileName().toString();
                String key = name.substring(0, name.length() -
                    (name.endsWith(".replies.json") ? ".replies.json".length() : ".json".length()));
                if (!visited.add(key)) {
                    continue;
                }
                try {
                    deleteFrom(key, pending);
                } catch (IOException error) {
                    // Continue cleaning other accounts in the same channel.
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        Files.deleteIfExists(pendingPath(channelId));
        blockedChannels.remove(channelId);
    }

    private Path pendingPath(String channelId) {
        return directory.resolve(channelId + ".deletion-pending.json");
    }

    private void deleteFrom(String key, Collection<String> messageIds) throws IOException {
        var saved = read(key);
        var replies = replies(key);
        int cut = saved.turns.size();
        boolean matched = false;
        for (String id : messageIds) {
            Integer index = saved.messageTurns.get(id);
            if (index != null) {
                matched = true;
                cut = Math.min(cut, index);
            } else if (saved.ids.contains(id) || replies.containsKey(id) || saved.postMessages.containsValue(id)) {
                // Legacy history has no per-turn Discord IDs.
                matched = true;
                cut = 0;
            }
        }
        if (matched == false) {
            return;
        }
        if (cut < saved.turns.size() && "assistant".equals(saved.turns.get(cut).get("role")) && cut > 0) {
            cut--;
        }
        int firstRemoved = cut;
        var removedIds = new HashSet<>(messageIds);
        saved.messageTurns.forEach((id, index) -> {
            if (index >= firstRemoved) {
                removedIds.add(id);
            }
        });
        if (cut == 0) {
            removedIds.addAll(replies.keySet());
            saved.ids.clear();
            saved.postMessages.clear();
        }
        saved.turns.subList(cut, saved.turns.size()).clear();
        saved.messageTurns.keySet().removeAll(removedIds);
        saved.ids.removeAll(removedIds);
        saved.postMessages.values().removeAll(removedIds);
        replies.keySet().removeAll(removedIds);
        // Keep nextPost monotonic: surviving Discord posts must not acquire duplicate numbers.
        // Clear cached bodies first; on failure the original mapping remains available for retry.
        PrivateFiles.write(replyPath(key), ChatGptAccounts.JSON.writeValueAsString(replies));
        save(key, saved);
    }

    /**
     * Bound inline attachment history as well as text, before sending and saving.
     */
    public static void trim(List<Map<String, Object>> turns) throws IOException {
        while (turns.size() > 40) {
            removeOldestExchange(turns);
        }
        while (turns.size() > 2 &&
            (turns.stream().mapToInt(t -> textLength(t.get("content"))).sum() > 64000 ||
                ChatGptAccounts.JSON.writeValueAsBytes(turns).length > 16 * 1024 * 1024)) {
            removeOldestExchange(turns);
        }
    }

    private static void removeOldestExchange(List<Map<String, Object>> turns) {
        turns.remove(0);
        if (!turns.isEmpty() && "assistant".equals(turns.getFirst().get("role"))) {
            turns.remove(0);
        }
    }

    private static int textLength(Object content) {
        if (content instanceof String text) {
            return text.length();
        }

        if (content instanceof List<?> parts) {
            return parts.stream().filter(p -> p instanceof Map<?, ?>).mapToInt(
                p -> {
                    Object text = ((Map<?, ?>) p).get("text");
                    return text instanceof String s ? s.length() : 0;
                }
            ).sum();
        }

        return 0;
    }

    private Path path(String key) {
        if (key.matches("[0-9a-f-]{1,160}") == false) {
            throw new IllegalArgumentException("Invalid conversation key.");
        }

        return directory.resolve(key + ".json");
    }
}
