package app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LimitCommandListenerTest {
    private final LimitCommandListener listener = new LimitCommandListener("123456789012345678");

    @Test void leavesOtherCommandsToTheirHandlers() {
        assertTrue(LimitCommandListener.handles("gvd", "limit"));
        assertFalse(LimitCommandListener.handles("other", "limit"));
        assertFalse(LimitCommandListener.handles("gvd", "skipch"));
        assertFalse(LimitCommandListener.handles("gvd", "readch"));
        assertFalse(LimitCommandListener.handles("gvd", null));
    }

    @Test void unavailableLimitsAreNotReportedAsZeroOrFull() {
        String reply = listener.replyFor("123456789012345678");
        assertTrue(reply.contains("取得できません"));
        assertTrue(reply.contains("未実装"));
        assertTrue(reply.contains("回復時刻: 不明"));
        assertTrue(reply.contains("https://chatgpt.com/settings/usage"));
        assertFalse(reply.contains("0%"));
        assertFalse(reply.contains("残量: 0"));
    }

    @Test void directMessagesAndOtherGuildsOnlyGetScopeGuidance() {
        for (String guild : new String[]{null, "987654321098765432"}) {
            String reply = listener.replyFor(guild);
            assertTrue(reply.contains("設定対象サーバー"));
            assertFalse(reply.contains("AI 接続"));
            assertFalse(reply.contains("https://"));
        }
    }
}
