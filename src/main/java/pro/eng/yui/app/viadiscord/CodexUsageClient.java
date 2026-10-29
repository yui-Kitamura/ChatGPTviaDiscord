package pro.eng.yui.app.viadiscord;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Short-lived read-only JSONL session. Never passes SIWC tokens or starts an inference turn. */
class CodexUsageClient {
    @FunctionalInterface interface Launcher { Process start(ProcessBuilder builder) throws IOException; }
    private final CodexUsageConfig config;
    private final Launcher launcher;

    CodexUsageClient(CodexUsageConfig config) { this(config, ProcessBuilder::start); }
    CodexUsageClient(CodexUsageConfig config, Launcher launcher) { this.config = config; this.launcher = launcher; }

    String query(String expectedEmail) {
        if (expectedEmail == null || expectedEmail.isBlank())
            return unavailable("Bot側のメールアドレスを確認できません。管理者が接続を確認してください。");
        Process process = null;
        var worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        Future<CodexUsageSnapshot> pending = null;
        try {
            var builder = new ProcessBuilder(config.executable().toString(), "app-server", "--listen", "stdio://",
                    "-c", "cli_auth_credentials_store=\"file\"");
            builder.directory(config.home().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Do not inherit Bot tokens, API keys, SIWC tokens, or the desktop's CODEX_HOME.
            var inherited = Map.copyOf(builder.environment());
            builder.environment().clear();
            for (String key : Set.of("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP", "HOME", "USERPROFILE",
                    "LOCALAPPDATA", "APPDATA", "LANG", "LC_ALL", "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR")) {
                inherited.forEach((name, value) -> { if (name.equalsIgnoreCase(key)) builder.environment().put(name, value); });
            }
            builder.environment().put("CODEX_HOME", config.home().toString());
            process = launcher.start(builder);
            Process child = process;
            pending = worker.submit(() -> read(child, expectedEmail));
            return pending.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS).display();
        } catch (TimeoutException e) {
            return unavailable("照会がタイムアウトしました。管理者がCodexの接続を確認してください。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("照会を中断しました。");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AccountMismatch)
                return unavailable("Codex側の接続が未認証・別アカウント、またはメールアドレス不明です。Botと同じアカウントで専用保存先へログインしてください。");
            return unavailable("照会に失敗しました。管理者がCodexのログイン・バージョン・通信を確認してください。");
        } catch (IOException | RuntimeException e) {
            return unavailable("Codexを起動できません。管理者が実行ファイルと専用保存先を確認してください。");
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
                try { process.getInputStream().close(); } catch (IOException ignored) {}
            }
            if (pending != null) pending.cancel(true);
            worker.shutdownNow();
        }
    }

    private static String unavailable(String reason) {
        return "Codex連携の利用枠（試行）: 取得できませんでした。\n" + reason + "\n残量・回復日時は不明です。\n";
    }

    private static CodexUsageSnapshot read(Process process, String expectedEmail) throws IOException {
        var writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var rpc = new Rpc(reader, writer);
        rpc.call(1, "initialize", Map.of("clientInfo", Map.of("name", "chatgptviadiscord_usage", "title", "ChatGPTviaDiscord usage", "version", "0.1.0")));
        rpc.send(Map.of("method", "initialized", "params", Map.of()));
        checkAccount(rpc.call(2, "account/read", Map.of("refreshToken", false)), expectedEmail);
        JsonNode limits = rpc.call(3, "account/rateLimits/read", Map.of());
        // Recheck identity before exposing values if the managed login changed during the read.
        checkAccount(rpc.call(4, "account/read", Map.of("refreshToken", false)), expectedEmail);
        return CodexUsageSnapshot.parse(limits, Instant.now());
    }

    private static void checkAccount(JsonNode result, String expectedEmail) throws AccountMismatch {
        var account = result.path("account");
        if (!"chatgpt".equals(account.path("type").asText())
                || !expectedEmail.equalsIgnoreCase(account.path("email").asText())) throw new AccountMismatch();
    }
    private static final class AccountMismatch extends IOException {}

    private static final class Rpc {
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private int bytes;
        Rpc(BufferedReader reader, BufferedWriter writer) { this.reader = reader; this.writer = writer; }

        void send(Map<String, ?> message) throws IOException {
            writer.write(ChatGptAccounts.JSON.writeValueAsString(message)); writer.newLine(); writer.flush();
        }

        JsonNode call(int id, String method, Map<String, ?> params) throws IOException {
            send(Map.of("id", id, "method", method, "params", params));
            for (int count = 0; count < 128; count++) {
                var line = new StringBuilder();
                for (int c; (c = reader.read()) != '\n';) {
                    if (c == -1 || ++bytes > 1_048_576 || line.length() >= 65_536) throw new IOException("Invalid Codex response.");
                    line.append((char)c);
                }
                JsonNode message = ChatGptAccounts.JSON.readTree(line.toString());
                if (message == null || !message.isObject()) throw new IOException("Invalid Codex response.");
                if (message.has("method")) {
                    if (message.has("id")) throw new IOException("Unexpected Codex server request.");
                    continue; // Notifications do not complete a pending call.
                }
                if (!message.path("id").isIntegralNumber() || message.path("id").asInt(-1) != id)
                    throw new IOException("Unexpected Codex response ID.");
                if (message.has("error") || !message.path("result").isObject()) throw new IOException("Codex request failed.");
                return message.path("result");
            }
            throw new IOException("Too many Codex notifications.");
        }
    }
}
