package pro.eng.yui.app.viadiscord;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Public SIWC/Responses endpoints only; never uses ChatGPT backend-api or Codex credentials. */
class OpenAiTransport {
    static final String RESOURCE="https://api.openai.com/v1";
    static final String AUTHORIZE="https://auth.openai.com/api/accounts/authorize";
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI tokenEndpoint, jwksEndpoint, api, revokeEndpoint;
    private final Duration timeout;
    private JWKSet keys;
    private Instant keysAt=Instant.EPOCH;
    OpenAiTransport(Duration timeout) {
        this(timeout,URI.create("https://auth.openai.com/api/accounts/oauth/token"),URI.create("https://auth.openai.com/.well-known/jwks.json"),URI.create(RESOURCE+"/"),URI.create("https://auth.openai.com/api/accounts/oauth/revoke"));
    }
    OpenAiTransport(Duration timeout,URI token,URI jwks,URI api,URI revoke) {
        this.timeout=timeout;this.tokenEndpoint=token;this.jwksEndpoint=jwks;this.api=api;this.revokeEndpoint=revoke;
    }
    static String form(Map<String,String> values) {
        return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    JsonNode tokens(Map<String,String> fields) throws IOException {
        var request=HttpRequest.newBuilder(tokenEndpoint).timeout(Duration.ofSeconds(30)).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
        return json(request);
    }
    synchronized JWKSet keys(String kid) throws IOException {
        if(keys==null||keys.getKeyByKeyId(kid)==null||keysAt.plusSeconds(3600).isBefore(Instant.now())) {
            try { keys=JWKSet.parse(json(HttpRequest.newBuilder(jwksEndpoint).timeout(Duration.ofSeconds(20)).GET().build()).toString());keysAt=Instant.now(); }
            catch(java.text.ParseException e){throw new IOException("Invalid OpenAI signing keys.");}
        }
        return keys;
    }
    void revoke(ChatGptAccounts.Account account) throws IOException {
        var fields=Map.of("token",account.refreshToken,"token_type_hint","refresh_token","client_id",account.clientId);
        var request=HttpRequest.newBuilder(revokeEndpoint).timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
        try(var body=send(request).body()) {} // send validates HTTP status; revocation returns an empty 200.
    }
    record Model(String slug,String name) {}
    List<Model> models(String token) throws IOException {
        var result=json(authorized(api.resolve("models"),token).GET().build());
        if(!result.path("models").isArray())throw new IOException("Invalid ChatGPT model catalog.");
        var models=new ArrayList<Model>();
        for(var model:result.path("models"))if("list".equals(model.path("visibility").asText())&&!model.path("slug").asText().isBlank())
            models.add(new Model(model.path("slug").asText(),model.path("display_name").asText()));
        return List.copyOf(models);
    }
    String respond(String token,String model,String instructions,List<Map<String,String>> history) throws IOException {
        String payload=ChatGptAccounts.JSON.writeValueAsString(Map.of("model",model,"instructions",instructions,"input",history,"store",false,"stream",true));
        var request=authorized(api.resolve("responses"),token).timeout(timeout).header("Content-Type","application/json").header("Accept","text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var response=send(request);
        try(var input=response.body();var timer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
            var stop=timer.schedule(()->{try{input.close();}catch(IOException ignored){}},timeout.toMillis(),TimeUnit.MILLISECONDS);
            try {
                var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
                var text=new StringBuilder();var data=new StringBuilder();boolean completed=false;
                int total=0;
                for(String line;(line=reader.readLine())!=null;) {
                    if((total+=line.length())>2_000_000)throw new IOException("ChatGPT response exceeds local limit.");
                    if(!line.isEmpty()) {if(line.startsWith("data:"))data.append(line.substring(5).stripLeading()).append('\n');continue;}
                    if(data.isEmpty())continue;
                    String value=data.toString().trim();data.setLength(0);
                    if("[DONE]".equals(value))break;
                    var event=ChatGptAccounts.JSON.readTree(value);String type=event.path("type").asText();
                    if("response.output_text.delta".equals(type)||"response.refusal.delta".equals(type))text.append(event.path("delta").asText());
                    if("response.failed".equals(type)||"error".equals(type))throw failure(event.path("response").has("error")?event.path("response"):event,200);
                    if("response.incomplete".equals(type))throw new ChatGptException("incomplete",200);
                    if("response.completed".equals(type)){completed=true;break;}
                }
                if(!completed||text.isEmpty())throw new ChatGptException("interrupted_stream",200);
                return text.toString();
            } catch(ChatGptException failure) {failure.requestId=requestId(response);throw failure;}
            finally {stop.cancel(false);}
        }
    }
    private HttpRequest.Builder authorized(URI uri,String token){return HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization","Bearer "+token);}
    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()/100!=2)try(var stream=response.body()){
                JsonNode error;
                try{error=ChatGptAccounts.JSON.readTree(readLimited(stream,65536));}catch(IOException e){error=ChatGptAccounts.JSON.createObjectNode();}
                var failure=failure(error,response.statusCode());failure.requestId=requestId(response);throw failure;
            }
            return response;
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("ChatGPT request interrupted.");}
    }
    private JsonNode json(HttpRequest request) throws IOException {
        try(var body=send(request).body()){
            byte[] bytes=readLimited(body,2_000_001);
            if(bytes.length>2_000_000)throw new IOException("ChatGPT response exceeds local limit.");
            return ChatGptAccounts.JSON.readTree(bytes);
        }
    }
    private byte[] readLimited(InputStream input,int count) throws IOException {
        try(var timer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())){
            var deadline=timer.schedule(()->{try{input.close();}catch(IOException ignored){}},timeout.toMillis(),TimeUnit.MILLISECONDS);
            try{return input.readNBytes(count);}finally{deadline.cancel(false);}
        }
    }
    private static String requestId(HttpResponse<?> response){
        String id=response.headers().firstValue("x-request-id").orElse("");
        return id.matches("[a-zA-Z0-9_-]{1,150}")?id:"";
    }
    private static ChatGptException failure(JsonNode node,int status) {
        var error=node.path("error");String code=error.isTextual()?error.asText():error.path("code").asText("request_failed");
        if(!code.matches("[a-zA-Z0-9_]{1,100}"))code="request_failed";
        return new ChatGptException(code,status);
    }
}
