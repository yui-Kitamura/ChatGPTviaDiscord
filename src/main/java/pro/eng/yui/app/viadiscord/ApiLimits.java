package pro.eng.yui.app.viadiscord;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only allowlisted numeric rate-limit headers, never arbitrary upstream text. */
record ApiLimits(Instant observedAt, Map<String, String> values, String outcome) {
    static ApiLimits from(HttpHeaders headers) {
        var values = new LinkedHashMap<String, String>();
        for (String dimension : new String[]{"requests", "tokens", "project-requests", "project-tokens"}) {
            for (String field : new String[]{"limit", "remaining", "reset"}) {
                String value = headers.firstValue("x-ratelimit-" + field + "-" + dimension).orElse("");
                String pattern = field.equals("reset") ? "(?:[0-9]{1,9}(?:\\.[0-9]{1,3})?(?:ms|s|m|h|d)){1,6}" : "[0-9]{1,18}";
                if (value.length() <= 64 && value.matches(pattern)) values.put(field + "-" + dimension, value);
            }
        }
        return new ApiLimits(Instant.now(), Map.copyOf(values), "received");
    }

    ApiLimits withOutcome(String result) { return new ApiLimits(observedAt, values, result); }

    String display() {
        var text = new StringBuilder("直近の AI 応答での観測: " + observedAt + "\n");
        text.append(switch (outcome) {
            case "success" -> "直近の生成: 完了\n";
            case "subscription_sharing_usage_limit_exceeded" -> "直近の生成: このアプリの ChatGPT 利用枠に到達（現在の回復状況は不明）\n";
            default -> "直近の生成: 完了を確認できません\n";
        });
        String[] dimensions = {"requests", "tokens", "project-requests", "project-tokens"};
        String[] labels = {"リクエスト", "トークン", "プロジェクトのリクエスト", "プロジェクトのトークン"};
        boolean found = false;
        for (int i = 0; i < dimensions.length; i++) {
            String dimension = dimensions[i];
            if (!values.containsKey("remaining-" + dimension) && !values.containsKey("limit-" + dimension)
                    && !values.containsKey("reset-" + dimension)) continue;
            found = true;
            text.append(labels[i]).append(": 残り ").append(values.getOrDefault("remaining-" + dimension, "不明"))
                    .append(" / 上限 ").append(values.getOrDefault("limit-" + dimension, "不明"))
                    .append("、リセットまで（観測時点から）: ").append(values.getOrDefault("reset-" + dimension, "不明")).append('\n');
        }
        if (!found) text.append("数値・リセット情報: API から提供されていません。\n");
        text.append("観測値は現在の残量・回復を保証しません。短時間の API 制限であり、ChatGPT プラン全体の残量ではありません。\n");
        return text.toString();
    }
}
