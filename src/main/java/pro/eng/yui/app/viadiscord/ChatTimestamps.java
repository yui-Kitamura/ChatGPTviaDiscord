package pro.eng.yui.app.viadiscord;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Persist timestamps separately from raw content; expose them as text at the API boundary. */
final class ChatTimestamps {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX 'JST'", Locale.ROOT);

    static String format(OffsetDateTime time) {
        return time == null ? "不明" : FORMAT.format(time.atZoneSameInstant(JST));
    }

    static Map<String,Object> turn(String role, Object content, OffsetDateTime time) {
        return Map.of("role", role, "content", content, "timestamp_jst", format(time));
    }

    static List<Map<String,Object>> input(List<Map<String,Object>> turns) {
        return turns.stream().map(turn -> {
            String label = "[発言日時: " + turn.getOrDefault("timestamp_jst", "不明（日時未記録の履歴）") + "]\n";
            Object content = turn.get("content");
            if (content instanceof String text) content = label + text;
            else {
                var parts = new ArrayList<Object>();
                parts.add(Map.of("type", "input_text", "text", label));
                parts.addAll((List<?>) content);
                content = parts;
            }
            return Map.<String,Object>of("role", turn.get("role"), "content", content);
        }).toList();
    }
}
