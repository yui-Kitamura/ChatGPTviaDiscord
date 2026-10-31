package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(AttachmentHttpFixture.class)
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
            Object input=AttachmentInput.content("hello",List.of(
                    AttachmentInputTest.attachment("a.png","image/png",new byte[]{1,2,3}),
                    AttachmentInputTest.attachment("a.pdf","application/pdf",new byte[]{4,5,6})));
            assertEquals("こんにちは",transport(server).respond("secret","available-model","guide",List.of(Map.of("role","user","content",input))));
            var r=request.get();assertFalse(r.path("store").asBoolean());assertTrue(r.path("stream").asBoolean());assertTrue(r.path("input").isArray());
            assertEquals(Set.of("model","instructions","input","store","stream"),toSet(r.fieldNames()));
            var content=r.path("input").get(0).path("content");
            assertEquals("hello",content.get(0).path("text").asText());
            assertEquals("data:image/png;base64,AQID",content.get(1).path("image_url").asText());
            assertEquals("data:application/pdf;base64,BAUG",content.get(2).path("file_data").asText());
        }finally{server.stop(0);}
    }
    @Test void reportsReceivingBeforeTheStreamCompletes() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var receiving=new java.util.concurrent.CountDownLatch(1);
        var stages=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/responses",e->{
            e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,0);
            e.getResponseBody().write("data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\n".getBytes(StandardCharsets.UTF_8));
            e.getResponseBody().flush();
            try {if(!receiving.await(1,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("Receiving callback missing");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new java.io.IOException(failure);}
            e.getResponseBody().write("data: {\"type\":\"response.completed\"}\n\n".getBytes(StandardCharsets.UTF_8));e.close();
        });server.start();
        try {
            var progress=new OpenAiTransport.Progress() {
                public void sent(){stages.add("sent");}
                public void receiving(){stages.add("receiving");receiving.countDown();}
                public void failed(){stages.add("failed");}
            };
            assertEquals("answer",transport(server).respond("secret","m","i",List.of(),progress));
            assertEquals(List.of("sent","receiving"),stages);
        } finally {server.stop(0);}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"http","failed","incomplete","refusal","malformed"})
    void ngResponseNeverReportsNormalReceiving(String kind) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var stages=new ArrayList<String>();
        server.createContext("/responses",e->{
            e.getRequestBody().readAllBytes();
            String body=switch(kind) {
                case "http" -> "{\"error\":{\"code\":\"rate_limit_exceeded\"}}";
                case "failed" -> "data: {\"type\":\"response.failed\"}\n\n";
                case "incomplete" -> "data: {\"type\":\"response.incomplete\"}\n\n";
                case "refusal" -> "data: {\"type\":\"response.refusal.delta\",\"delta\":\"refused\"}\n\ndata: {\"type\":\"response.completed\"}\n\n";
                default -> "data: invalid-json\n\n";
            };
            byte[] data=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(kind.equals("http")?429:200,data.length);e.getResponseBody().write(data);e.close();
        });server.start();
        try {
            var progress=new OpenAiTransport.Progress() {
                public void sent(){stages.add("sent");}
                public void receiving(){stages.add("receiving");}
                public void failed(){stages.add("failed");}
            };
            if(kind.equals("refusal"))assertEquals("refused",transport(server).respond("secret","m","i",List.of(),progress));
            else assertThrows(java.io.IOException.class,()->transport(server).respond("secret","m","i",List.of(),progress));
            assertEquals(List.of("sent","failed"),stages);
        } finally {server.stop(0);}
    }
    private static Set<String> toSet(Iterator<String> i){var set=new HashSet<String>();i.forEachRemaining(set::add);return set;}
    @Test void oauthDescriptionsBecomeOnlyControlledHintsAndNeverLeakRawText() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var description=new AtomicReference<String>("");
        server.createContext("/token",e->{
            e.getRequestBody().readAllBytes();var body=new HashMap<String,String>();body.put("error","invalid_grant");
            if(!description.get().isEmpty())body.put("error_description",description.get());
            byte[] data=ChatGptAccounts.JSON.writeValueAsBytes(body);e.getResponseHeaders().set("x-request-id","request-test");
            e.sendResponseHeaders(400,data.length);e.getResponseBody().write(data);e.close();
        });server.start();
        try {
            var cases=Map.of("",ChatGptException.DescriptionHint.NOT_PROVIDED,
                    "PKCE code_verifier mismatch private-secret",ChatGptException.DescriptionHint.PKCE,
                    "redirect_uri mismatch private-secret",ChatGptException.DescriptionHint.REDIRECT_URI,
                    "Authorization code invalid or expired private-secret",ChatGptException.DescriptionHint.AUTHORIZATION_CODE,
                    "client_id mismatch private-secret",ChatGptException.DescriptionHint.CLIENT_BINDING,
                    "private-secret",ChatGptException.DescriptionHint.OTHER);
            for(var entry:cases.entrySet()) {
                description.set(entry.getKey());var failure=assertThrows(ChatGptException.class,()->transport(server).tokens(Map.of("code","secret")));
                assertEquals("invalid_grant",failure.code);assertEquals(400,failure.status);assertEquals("request-test",failure.requestId);
                assertEquals(entry.getValue(),failure.descriptionHint);assertFalse(failure.getMessage().contains("private-secret"));
            }
        }finally{server.stop(0);}
    }
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
