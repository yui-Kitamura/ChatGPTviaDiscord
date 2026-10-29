package pro.eng.yui.app.viadiscord;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;

/** Only numeric quota fields and bounded identifiers may reach the public reply. */
record CodexUsageSnapshot(Instant observedAt, java.util.List<String> windows, boolean truncated) {
    static CodexUsageSnapshot parse(JsonNode result, Instant observedAt) {
        var lines = new ArrayList<String>();
        JsonNode buckets = result.path("rateLimitsByLimitId");
        boolean truncated = false;
        if (buckets.isObject()) {
            var fields = buckets.fields();
            int count = 0;
            while (fields.hasNext()) {
                var field = fields.next();
                if (count++ >= 4) { truncated = true; break; }
                add(lines, field.getValue(), identifier(field.getKey()));
            }
        } else if (result.path("rateLimits").isObject()) {
            var bucket = result.path("rateLimits");
            add(lines, bucket, identifier(bucket.path("limitId").asText("unknown")));
        }
        return new CodexUsageSnapshot(observedAt, java.util.List.copyOf(lines), truncated);
    }

    private static String identifier(String value) {
        return value.matches("[a-zA-Z0-9_-]{1,40}") ? value : "unknown";
    }

    private static void add(ArrayList<String> lines, JsonNode bucket, String id) {
        for (String kind : new String[]{"primary", "secondary"}) {
            JsonNode window = bucket.path(kind);
            if (!window.isObject()) continue;
            JsonNode used = window.path("usedPercent"), minutes = window.path("windowDurationMins"), reset = window.path("resetsAt");
            String remaining = "不明";
            if (used.isNumber() && Double.isFinite(used.asDouble()) && used.asDouble() >= 0 && used.asDouble() <= 100)
                remaining = String.format(Locale.ROOT, "%.1f%%", 100 - used.asDouble());
            String period = minutes.isIntegralNumber() && minutes.canConvertToInt() && minutes.asInt() > 0
                    ? minutes.asInt() + "分枠" : kind;
            // Discord renders the absolute timestamp in each reader's local timezone.
            String recovery = reset.isIntegralNumber() && reset.canConvertToLong() && reset.asLong() > 0 && reset.asLong() <= 253402300799L
                    ? "<t:" + reset.asLong() + ":f>" : "不明";
            lines.add(id + " / " + period + ": 残り " + remaining + "、回復日時 " + recovery);
        }
    }

    String display() {
        return "Codex連携の利用枠（試行）\nBotと同じメールアドレスの接続を確認\n取得時刻: <t:" + observedAt.getEpochSecond() + ":f>\n"
                + (windows.isEmpty() ? "残量・回復日時: 提供されていません。\n" : String.join("\n", windows) + "\n")
                + (truncated ? "表示は先頭4枠までです。\n" : "")
                + "取得できた利用枠のみを表示しています。ChatGPT全機能の残量や、このBotの利用可否を保証しません。\n";
    }
}
