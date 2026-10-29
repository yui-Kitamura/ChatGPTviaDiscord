package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexUsageClientTest {
    @TempDir Path directory;

    private String query(String mode, Duration timeout, AtomicReference<Process> child) {
        var config = new CodexUsageConfig(true, directory.resolve("codex"), directory, timeout);
        var client = new CodexUsageClient(config, builder -> {
            assertEquals(List.of(config.executable().toString(), "app-server", "--listen", "stdio://", "-c", "cli_auth_credentials_store=\"file\""), builder.command());
            assertEquals(directory.toString(), builder.environment().get("CODEX_HOME"));
            assertFalse(builder.environment().containsKey("OPENAI_API_KEY"));
            assertFalse(builder.environment().containsKey("DISCORD_BOT_TOKEN"));
            assertFalse(builder.environment().containsKey("ACCESS_TOKEN"));
            assertEquals(directory.toFile(), builder.directory());
            assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError());
            builder.command(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("java.class.path"), Peer.class.getName(), mode);
            Process process = builder.start();child.set(process);return process;
        });
        return client.query("bot@example.com");
    }

    @Test void followsHandshakeChecksIdentityAndNeverStartsAThreadOrTurn() throws Exception {
        var child = new AtomicReference<Process>();
        String text = query("success", Duration.ofSeconds(10), child);
        assertTrue(text.contains("残り 75.0%"), text);
        assertTrue(text.contains("<t:1800001000:f>"));
        assertFalse(text.contains("bot@example.com"));
        assertTrue(child.get().waitFor(3, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test void rejectsWrongOrMissingAccountIncludingAnIdentityChange() {
        for (String mode : new String[]{"wrong-account", "no-account", "api-key", "changed-account", "no-email"}) {
            String text = query(mode, Duration.ofSeconds(10), new AtomicReference<>());
            assertTrue(text.contains("未認証・別アカウント"), mode + ": " + text);
            assertFalse(text.contains("75.0%"));assertFalse(text.contains("other@example.com"));
        }
    }

    @Test void hidesErrorsRejectsBadProtocolAndBoundsOutput() {
        for (String mode : new String[]{"rpc-error", "wrong-id", "malformed", "huge-line", "server-request", "exit"}) {
            String text = query(mode, Duration.ofSeconds(10), new AtomicReference<>());
            assertTrue(text.contains("照会に失敗"), mode + ": " + text);
            assertFalse(text.contains("upstream-secret"));assertFalse(text.contains("75.0%"));
        }
    }

    @Test void deadlineTerminatesHangingChildAndDoesNotInventAValue() throws Exception {
        var child = new AtomicReference<Process>();
        long start = System.nanoTime();
        String text = query("hang", Duration.ofMillis(300), child);
        assertTrue(text.contains("タイムアウト"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
        assertTrue(child.get().waitFor(3, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test void missingEmailDoesNotStartAProcessAndLaunchFailureIsSanitized() {
        var config = new CodexUsageConfig(true, directory.resolve("codex"), directory, Duration.ofSeconds(1));
        var client = new CodexUsageClient(config, builder -> { throw new IOException("upstream-secret"); });
        assertTrue(client.query("").contains("Bot側のメールアドレス"));
        String text = client.query("bot@example.com");
        assertTrue(text.contains("起動できません"));assertFalse(text.contains("upstream-secret"));
    }

    /** A real subprocess emulating only the four read-only protocol requests. */
    public static class Peer {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            if (mode.equals("hang")) { Thread.sleep(30000); return; }
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            var output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
            String[] methods = {"initialize", "account/read", "account/rateLimits/read", "account/read"};
            for (int i = 0; i < methods.length; i++) {
                var request = ChatGptAccounts.JSON.readTree(input.readLine());
                if (!methods[i].equals(request.path("method").asText())) return;
                if (i == 0 && !request.path("params").path("clientInfo").path("name").asText().equals("chatgptviadiscord_usage")) return;
                int id = request.path("id").asInt();
                if (mode.equals("exit")) return;
                if (mode.equals("malformed")) { output.println("upstream-secret"); return; }
                if (mode.equals("huge-line")) { output.println("x".repeat(70000)); return; }
                if (mode.equals("rpc-error")) { output.println("{\"id\":" + id + ",\"error\":{\"message\":\"upstream-secret\"}}"); return; }
                if (mode.equals("server-request")) { output.println("{\"id\":999,\"method\":\"dangerous\"}"); return; }
                if (mode.equals("wrong-id")) { output.println("{\"id\":999,\"result\":{}}"); return; }
                String result = "{}";
                if (i == 1 || i == 3) {
                    String email = mode.equals("wrong-account") || (mode.equals("changed-account") && i == 3) ? "other@example.com" : "BOT@example.com";
                    result = "{\"account\":{\"type\":\"chatgpt\",\"email\":\"" + email + "\"}}";
                    if (mode.equals("no-account")) result = "{\"account\":null}";
                    if (mode.equals("api-key")) result = "{\"account\":{\"type\":\"apiKey\"}}";
                    if (mode.equals("no-email")) result = "{\"account\":{\"type\":\"chatgpt\",\"email\":null}}";
                }
                if (i == 2) result = "{\"rateLimits\":{\"limitId\":\"codex\",\"primary\":{\"usedPercent\":25,\"windowDurationMins\":300,\"resetsAt\":1800001000}}}";
                output.println("{\"method\":\"account/rateLimits/updated\",\"params\":{}}");
                output.println("{\"id\":" + id + ",\"result\":" + result + "}");
                if (i == 0) {
                    var initialized = ChatGptAccounts.JSON.readTree(input.readLine());
                    if (!initialized.path("method").asText().equals("initialized") || initialized.has("id")) return;
                }
            }
            // Keep the pipes open until the client closes its short-lived session.
            input.readLine();
        }
    }
}
