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
    private final Map<String,ApiLimits> limits=new LinkedHashMap<>();
    private static String limitKey(String token,String model) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)))+":"+model;}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    synchronized ApiLimits limits(String token,String model) {return limits.get(limitKey(token,model));}
    private synchronized void observe(String token,String model,HttpResponse<?> response) {
        String key=limitKey(token,model);
        limits.remove(key);limits.put(key,ApiLimits.from(response.headers()));
        while(limits.size()>64)limits.remove(limits.keySet().iterator().next());
    }
    private synchronized void outcome(String token,String model,String result) {
        String key=limitKey(token,model);var observed=limits.get(key);
        if(observed!=null)limits.put(key,observed.withOutcome(result));
    }
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
            catch(java.text.ParseException e){throw new IOException("Invalid OpenAI signing keys.",e);}
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
    interface Progress {
        Progress NONE = new Progress() {};
        default void sent() {}
        default void receiving() {}
        default void failed() {}
        default void sources(List<String> urls) {}
    }
    String respond(String token,String model,String instructions,List<Map<String,Object>> history) throws IOException {
        return respond(token,model,instructions,history,Progress.NONE);
    }
    String respond(String token,String model,String instructions,List<Map<String,Object>> history,Progress progress) throws IOException {
        try {return respondStream(token,model,instructions,history,progress);}
        catch(IOException | RuntimeException failure) {progress.failed();throw failure;}
    }
    private String respondStream(String token,String model,String instructions,List<Map<String,Object>> history,Progress progress) throws IOException {
        String payload=ChatGptAccounts.JSON.writeValueAsString(Map.of("model",model,"instructions",instructions,"input",ChatTimestamps.input(history),"store",false,"stream",true,
                "tools",List.of(Map.of("type","web_search","external_web_access",true)),"tool_choice","auto"));
        var request=authorized(api.resolve("responses"),token).timeout(timeout).header("Content-Type","application/json").header("Accept","text/event-stream")
                .POST(publishing(payload,progress)).build();
        HttpResponse<InputStream> response;
        try {response=send(request,r->observe(token,model,r));}
        catch(ChatGptException failure){outcome(token,model,failure.code);throw failure;}
        try(var input=response.body();var timer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
            var stop=timer.schedule(()->{try{input.close();}catch(IOException ignored){}},timeout.toMillis(),TimeUnit.MILLISECONDS);
            try {
                var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
                var text=new StringBuilder();var data=new StringBuilder();boolean completed=false,receiving=false,rejected=false;
                var sources=new LinkedHashSet<String>();
                int total=0;
                for(String line;(line=reader.readLine())!=null;) {
                    if((total+=line.length())>2_000_000)throw new IOException("ChatGPT response exceeds local limit.");
                    if(!line.isEmpty()) {if(line.startsWith("data:"))data.append(line.substring(5).stripLeading()).append('\n');continue;}
                    if(data.isEmpty())continue;
                    String value=data.toString().trim();data.setLength(0);
                    if("[DONE]".equals(value))break;
                    var event=ChatGptAccounts.JSON.readTree(value);String type=event.path("type").asText();
                    if("response.refusal.delta".equals(type)) {rejected=true;progress.failed();}
                    if("response.output_text.delta".equals(type)&&!receiving&&!rejected&&!event.path("delta").asText().isEmpty()) {
                        receiving=true;progress.receiving();
                    }
                    if("response.output_text.delta".equals(type)||"response.refusal.delta".equals(type))text.append(event.path("delta").asText());
                    if("response.output_text.annotation.added".equals(type))collectCitation(event.path("annotation"),sources);
                    if("response.failed".equals(type)||"error".equals(type))throw failure(event.path("response").has("error")?event.path("response"):event,200);
                    if("response.incomplete".equals(type))throw new ChatGptException("incomplete",200);
                    if("response.completed".equals(type)){
                        for(var item:event.path("response").path("output"))
                            if("message".equals(item.path("type").asText()))for(var content:item.path("content"))
                                for(var annotation:content.path("annotations"))collectCitation(annotation,sources);
                        completed=true;break;
                    }
                }
                if(!completed||text.isEmpty())throw new ChatGptException("interrupted_stream",200);
                outcome(token,model,"success");progress.sources(List.copyOf(sources));return text.toString();
            } catch(ChatGptException failure) {outcome(token,model,failure.code);failure.requestId=requestId(response);throw failure;}
            catch(IOException failure) {outcome(token,model,"failed");throw failure;}
            finally {stop.cancel(false);}
        }
    }
    // Signal after the HTTP client has consumed the request body, before it can receive a response.
    private static HttpRequest.BodyPublisher publishing(String payload,Progress progress) {
        var body=HttpRequest.BodyPublishers.ofString(payload);
        return new HttpRequest.BodyPublisher() {
            public long contentLength() {return body.contentLength();}
            public void subscribe(Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                body.subscribe(new Flow.Subscriber<java.nio.ByteBuffer>() {
                    public void onSubscribe(Flow.Subscription subscription) {subscriber.onSubscribe(subscription);}
                    public void onNext(java.nio.ByteBuffer bytes) {subscriber.onNext(bytes);}
                    public void onError(Throwable failure) {subscriber.onError(failure);}
                    public void onComplete() {progress.sent();subscriber.onComplete();}
                });
            }
        };
    }
    private static void collectCitation(JsonNode annotation,Set<String> sources) {
        if(!"url_citation".equals(annotation.path("type").asText()))return;
        String url=annotation.path("url").asText();
        try {
            URI uri=URI.create(url);
            if(("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))
                    &&uri.getHost()!=null&&uri.getRawUserInfo()==null
                    &&url.chars().noneMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)||c=='<'||c=='>'))sources.add(url);
        }catch(IllegalArgumentException ignored){}
    }
    private HttpRequest.Builder authorized(URI uri,String token){return HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization","Bearer "+token);}
    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        return send(request,response->{});
    }
    private HttpResponse<InputStream> send(HttpRequest request,java.util.function.Consumer<HttpResponse<InputStream>> observer) throws IOException {
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            observer.accept(response);
            if(response.statusCode()/100!=2)try(var stream=response.body()){
                JsonNode error;
                try{error=ChatGptAccounts.JSON.readTree(readLimited(stream,65536));}catch(IOException e){error=ChatGptAccounts.JSON.createObjectNode();}
                var failure=failure(error,response.statusCode());failure.requestId=requestId(response);throw failure;
            }
            return response;
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("ChatGPT request interrupted.",e);}
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
        var failure=new ChatGptException(code,status);
        // Descriptions are untrusted prose, not protocol reason codes. Retain only a coarse hint.
        String description=node.path("error_description").asText(error.path("message").asText("")).toLowerCase(Locale.ROOT);
        failure.descriptionHint=description.isBlank()?ChatGptException.DescriptionHint.NOT_PROVIDED
                :description.contains("pkce")||description.contains("code_verifier")||description.contains("code verifier")?ChatGptException.DescriptionHint.PKCE
                :description.contains("redirect_uri")||description.contains("redirect uri")?ChatGptException.DescriptionHint.REDIRECT_URI
                :description.contains("authorization code")||description.contains("authorization_code")?ChatGptException.DescriptionHint.AUTHORIZATION_CODE
                :description.contains("client_id")||description.contains("client id")?ChatGptException.DescriptionHint.CLIENT_BINDING
                :ChatGptException.DescriptionHint.OTHER;
        return failure;
    }
}
