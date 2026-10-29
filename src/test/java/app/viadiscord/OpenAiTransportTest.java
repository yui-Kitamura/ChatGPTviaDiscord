package app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class OpenAiTransportTest {
    OpenAiTransport transport(HttpServer server){var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");return new OpenAiTransport(Duration.ofSeconds(2),uri.resolve("token"),uri.resolve("jwks"),uri,uri.resolve("revoke"));}
    @Test void streamsTextAndRequiresCompletedWithSupportedFieldsOnly() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var request=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext("/responses",e->{
            assertEquals("Bearer secret",e.getRequestHeaders().getFirst("Authorization"));request.set(ChatGptAccounts.JSON.readTree(e.getRequestBody()));
            byte[] data=("data: {\"type\":\"response.output_text.delta\",\"delta\":\"こんにちは\"}\n\n"+"data: {\"type\":\"response.completed\"}\n\n").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();
        });server.start();
        try {
            assertEquals("こんにちは",transport(server).respond("secret","available-model","guide",List.of(Map.of("role","user","content","hello"))));
            var r=request.get();assertFalse(r.path("store").asBoolean());assertTrue(r.path("stream").asBoolean());assertTrue(r.path("input").isArray());
            assertEquals(Set.of("model","instructions","input","store","stream"),toSet(r.fieldNames()));
        }finally{server.stop(0);}
    }
    private static Set<String> toSet(Iterator<String> i){var set=new HashSet<String>();i.forEachRemaining(set::add);return set;}
    @Test void partialTextAndUsageFailureNeverBecomeSuccessfulAnswers() throws Exception {
        for(String ending:List.of("", "data: {\"type\":\"response.incomplete\"}\n\n", "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"private upstream data\"}}}\n\n")) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/responses",e->{e.getRequestBody().readAllBytes();byte[] data=("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"+ending).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();});server.start();
            try{var error=assertThrows(ChatGptException.class,()->transport(server).respond("secret","m","i",List.of()));assertFalse(error.getMessage().contains("private"));if(ending.contains("usage_limit"))assertEquals("subscription_sharing_usage_limit_exceeded",error.code);}
            finally{server.stop(0);}
        }
    }
    @Test void catalogIsAccountSpecificAndDirectAdmissionErrorsAreHandled() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/models",e->{int call=calls.incrementAndGet();String value=call==1?"{\"models\":[{\"slug\":\"visible\",\"display_name\":\"Visible\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hidden\"}]}":"{\"detail\":\"restricted private detail\"}";byte[] bytes=value.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(call==1?200:403,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try{
            var t=transport(server);assertEquals(List.of(new OpenAiTransport.Model("visible","Visible")),t.models("secret"));
            var error=assertThrows(ChatGptException.class,()->t.models("secret"));assertEquals(403,error.status);assertFalse(error.getMessage().contains("private"));assertEquals(2,calls.get());
        }finally{server.stop(0);}
    }
}
