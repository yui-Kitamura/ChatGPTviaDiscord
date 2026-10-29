package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ApiLimitsTest {
    @Test void rejectsUntrustedOrMalformedHeadersAndDoesNotInferMissingValues() {
        var limits = ApiLimits.from(HttpHeaders.of(Map.of(
                "x-ratelimit-limit-tokens", List.of("1000"),
                "x-ratelimit-remaining-tokens", List.of("@everyone secret"),
                "x-ratelimit-reset-tokens", List.of("1m2.5s"),
                "x-ratelimit-remaining-requests", List.of("-1"),
                "x-account-email", List.of("private@example.com")), (a,b) -> true));
        String reply = limits.display();
        assertTrue(reply.contains("残り 不明 / 上限 1000"));assertTrue(reply.contains("1m2.5s"));
        assertFalse(reply.contains("secret"));assertFalse(reply.contains("private@"));assertFalse(reply.contains("@everyone"));
        assertFalse(reply.contains("残り 0"));
    }

    @Test void observesSuccessAndHttpAndStreamErrorsWithoutReusingOtherCredentialsOrModels() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/responses", e -> {
            e.getRequestBody().readAllBytes();int call = calls.incrementAndGet();
            if (call == 1 || call == 3) {
                e.getResponseHeaders().set("x-ratelimit-limit-tokens", "100");
                e.getResponseHeaders().set("x-ratelimit-remaining-tokens", call == 1 ? "42" : "0");
                e.getResponseHeaders().set("x-ratelimit-reset-tokens", "1m");
            }
            String body = call == 3 ? "{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}"
                    : call == 4 ? "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
                    : "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\ndata: {\"type\":\"response.completed\"}\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(call == 3 ? 429 : 200, bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            var transport = new OpenAiTransport(Duration.ofSeconds(3), uri, uri, uri, uri);
            assertNull(transport.limits("secret", "m"));
            transport.respond("secret", "m", "guide", List.of());
            assertTrue(transport.limits("secret", "m").display().contains("残り 42 / 上限 100"));
            assertEquals("success", transport.limits("secret", "m").outcome());
            assertNull(transport.limits("another-token", "m"));assertNull(transport.limits("secret", "another-model"));
            transport.respond("secret", "m", "guide", List.of());
            assertTrue(transport.limits("secret", "m").values().isEmpty());
            assertThrows(ChatGptException.class, () -> transport.respond("secret", "m", "guide", List.of()));
            assertTrue(transport.limits("secret", "m").display().contains("利用枠に到達"));
            assertTrue(transport.limits("secret", "m").display().contains("残り 0"));
            assertThrows(ChatGptException.class, () -> transport.respond("secret", "m", "guide", List.of()));
            assertTrue(transport.limits("secret", "m").values().isEmpty());
            assertEquals("subscription_sharing_usage_limit_exceeded", transport.limits("secret", "m").outcome());
        } finally { server.stop(0); }
    }
}
