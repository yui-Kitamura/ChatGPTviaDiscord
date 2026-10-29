package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CodexUsageSnapshotTest {
    private String display(String json) throws Exception {
        return CodexUsageSnapshot.parse(ChatGptAccounts.JSON.readTree(json), Instant.ofEpochSecond(1800000000)).display();
    }

    @Test void prefersNamedBucketsAndShowsBothWindowsAndFetchTime() throws Exception {
        String result = display("""
                {"rateLimits":{"primary":{"usedPercent":99}},"rateLimitsByLimitId":{
                  "codex":{"primary":{"usedPercent":25,"windowDurationMins":300,"resetsAt":1800001000},
                           "secondary":{"usedPercent":42.5,"windowDurationMins":10080,"resetsAt":1800002000}},
                  "other":{"primary":{"usedPercent":100}}}}
                """);
        assertTrue(result.contains("codex / 300分枠: 残り 75.0%"));
        assertTrue(result.contains("10080分枠: 残り 57.5%"));
        assertTrue(result.contains("other / primary: 残り 0.0%"));
        assertTrue(result.contains("回復日時 <t:1800001000:f>"));
        assertTrue(result.contains("取得時刻: <t:1800000000:f>"));
        assertFalse(result.contains("残り 1.0%"));
    }

    @Test void supportsLegacyOnlyAndKeepsMissingAndInvalidValuesUnknown() throws Exception {
        assertTrue(display("{\"rateLimits\":{\"limitId\":\"codex\",\"primary\":{\"usedPercent\":0}}}").contains("残り 100.0%"));
        for (String json : new String[]{"{}", "{\"rateLimits\":null}", "{\"rateLimitsByLimitId\":{}}"}) {
            String result = display(json);
            assertTrue(result.contains("提供されていません"));
            assertFalse(result.contains("100.0%"));assertFalse(result.contains("残り 0.0%"));
        }
        String result = display("""
                {"rateLimits":{"primary":{"usedPercent":null,"resetsAt":-1},
                  "secondary":{"usedPercent":101,"windowDurationMins":-1,"resetsAt":253402300800}}}
                """);
        assertTrue(result.contains("残り 不明、回復日時 不明"));assertFalse(result.contains("<t:-1"));
    }

    @Test void arbitraryTextIsNotPublishedAndOutputFitsDiscord() throws Exception {
        var result = ChatGptAccounts.JSON.createObjectNode();
        var buckets = result.putObject("rateLimitsByLimitId");
        for (int i = 0; i < 20; i++) {
            var bucket = buckets.putObject("x".repeat(39) + i);
            bucket.put("limitName", "private@example.com @everyone secret-token");
            for (String kind : new String[]{"primary", "secondary"}) {
                var window = bucket.putObject(kind);
                window.put("usedPercent", 50);window.put("windowDurationMins", Integer.MAX_VALUE);window.put("resetsAt", 253402300799L);
            }
        }
        String text = CodexUsageSnapshot.parse(result, Instant.now()).display();
        assertTrue(text.length() < 1500);
        assertTrue(text.contains("先頭4枠"));
        assertFalse(text.contains("private"));assertFalse(text.contains("@everyone"));assertFalse(text.contains("secret-token"));
        assertTrue(display("{\"rateLimitsByLimitId\":{\"@everyone\":{\"primary\":{}}}}").contains("unknown / primary"));
    }
}
