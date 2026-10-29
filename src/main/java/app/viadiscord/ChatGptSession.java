package app.viadiscord;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One-use loopback PKCE flow. Only account owner interaction hooks see login/status results. */
final class ChatGptSession implements AutoCloseable {
    private static final String SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final ChatGptAccounts accounts;
    private final OpenAiTransport transport;
    private final HttpServer server;
    private final ExecutorService callbacks=Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String,Attempt> attempts=new HashMap<>();
    private final Map<String,Long> generations=new HashMap<>();
    private final Set<String> unsafeRefresh=new HashSet<>();
    private final String callback;
    private record Attempt(String key,String state,String nonce,String verifier,String ticket,String client,String subject,
                           String hint,boolean needsConsent,Instant expires,long generation,BooleanSupplier permitted,Consumer<String> result) {}
    ChatGptSession(ChatGptAccounts accounts,OpenAiTransport transport,int port) throws IOException {
        this.accounts=accounts;this.transport=transport;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),8);
        callback="http://127.0.0.1:"+server.getAddress().getPort()+"/auth/callback";
        server.createContext("/auth/start",this::start);
        server.createContext("/auth/callback",this::complete);
        server.setExecutor(callbacks);server.start();
    }
    synchronized String login(String user,String label,BooleanSupplier permitted,Consumer<String> result) throws IOException {
        if(!permitted.getAsBoolean())throw new IOException("AI role is required.");
        String key=ChatGptAccounts.key(user,label);var old=accounts.get(key);
        attempts.values().removeIf(a->a.expires.isBefore(Instant.now())||a.key.startsWith(user+":"));
        if(attempts.size()>=64)throw new IOException("Too many pending sign-ins.");
        var a=new Attempt(key,random(),random(),random(),random(),old==null?"":old.clientId,old==null?"":old.subject,
                old==null?"":old.idToken,old!=null&&!old.canInvoke(),Instant.now().plusSeconds(600),generations.merge(key,1L,Long::sum),permitted,result);
        attempts.put(a.state,a);
        return callback.replace("/callback","/start")+"?ticket="+a.ticket;
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    String authorization(Attempt a) throws IOException {
        var values=new LinkedHashMap<String,String>();
        values.put("client_id",a.client.isEmpty()?"dynamic_agent_client":a.client);
        if(a.client.isEmpty())values.put("agent_name_hint","ChatGPTviaDiscord");
        else if(!a.hint.isEmpty())values.put("id_token_hint",a.hint);
        if(a.needsConsent)values.put("prompt","consent");
        values.put("ext_agent_host_id",accounts.hostId());values.put("response_type","code");values.put("redirect_uri",callback);
        values.put("scope",SCOPES);values.put("resource",OpenAiTransport.RESOURCE);values.put("state",a.state);values.put("nonce",a.nonce);
        values.put("code_challenge_method","S256");
        try{values.put("code_challenge",Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(a.verifier.getBytes(StandardCharsets.US_ASCII))));}
        catch(NoSuchAlgorithmException e){throw new IOException("PKCE is unavailable.");}
        return OpenAiTransport.AUTHORIZE+"?"+OpenAiTransport.form(values);
    }
    private void start(HttpExchange exchange) throws IOException {
        var stage=AuthDiagnostics.Stage.START_REQUEST;
        try {
            if(!validRequest(exchange,"/auth/start")){AuthDiagnostics.failure(stage,new IOException(),"");reply(exchange,400,"Invalid request.");return;}
            String ticket=query(exchange).get("ticket");Attempt found;
            synchronized(this){found=attempts.values().stream().filter(a->a.ticket.equals(ticket)&&a.expires.isAfter(Instant.now())).findFirst().orElse(null);}
            if(found==null||!found.permitted.getAsBoolean()){AuthDiagnostics.failure(stage,new IOException(),"");reply(exchange,400,"Sign-in link expired. Run /gvd auth again.");return;}
            stage=AuthDiagnostics.Stage.START_REDIRECT;
            headers(exchange);exchange.getResponseHeaders().set("Location",authorization(found));exchange.sendResponseHeaders(302,-1);exchange.close();
        }catch(Exception error){AuthDiagnostics.failure(stage,error,"");reply(exchange,400,"Sign-in could not be started. Run /gvd auth again.");}
    }
    private void complete(HttpExchange exchange) throws IOException {
        Attempt attempt=null;
        var stage=AuthDiagnostics.Stage.CALLBACK_REQUEST;
        String callbackError="";
        try {
            if(!validRequest(exchange,"/auth/callback")){AuthDiagnostics.failure(stage,new IOException(),"");reply(exchange,400,"Invalid request.");return;}
            stage=AuthDiagnostics.Stage.CALLBACK_QUERY;
            var query=query(exchange);String state=query.get("state");
            stage=AuthDiagnostics.Stage.CALLBACK_STATE;
            synchronized(this){if(state!=null)attempt=attempts.remove(state);}
            if(attempt==null||attempt.expires.isBefore(Instant.now())||!attempt.permitted.getAsBoolean()){
                AuthDiagnostics.failure(stage,new IOException(),"");
                reply(exchange,400,"Sign-in expired or invalid. Run /gvd auth again.");return;
            }
            stage=AuthDiagnostics.Stage.CALLBACK_CONSENT;
            if(query.containsKey("error")){callbackError=query.get("error");throw new IOException("Authorization declined.");}
            stage=AuthDiagnostics.Stage.CALLBACK_CLIENT;
            String client=query.getOrDefault("client_id",attempt.client);
            if(client.isBlank()||"dynamic_agent_client".equals(client)||(!attempt.client.isEmpty()&&!attempt.client.equals(client)))
                throw new IOException("Invalid issued OAuth client.");
            stage=AuthDiagnostics.Stage.CALLBACK_CODE;
            String code=query.get("code");if(code==null||code.isBlank())throw new IOException("Missing authorization code.");
            stage=AuthDiagnostics.Stage.TOKEN_EXCHANGE;
            var response=transport.tokens(Map.of("grant_type","authorization_code","client_id",client,"code",code,
                    "code_verifier",attempt.verifier,"redirect_uri",callback,"resource",OpenAiTransport.RESOURCE));
            stage=AuthDiagnostics.Stage.ID_TOKEN_PARSE;
            var token=SignedJWT.parse(response.path("id_token").asText());
            stage=AuthDiagnostics.Stage.SIGNING_KEYS;
            var signingKeys=transport.keys(token.getHeader().getKeyID());
            stage=AuthDiagnostics.Stage.ID_TOKEN_VERIFY;
            var identity=OpenAiIdentity.verify(token.serialize(),client,attempt.nonce,attempt.subject.isEmpty()?null:attempt.subject,
                    signingKeys,Instant.now());
            stage=AuthDiagnostics.Stage.TOKEN_RESPONSE;
            var account=fromTokens(response,null);
            account.clientId=client;account.subject=identity.subject();account.email=Objects.toString(identity.email(),"");
            var previous=accounts.get(attempt.key);if(previous!=null)account.model=previous.model;
            stage=AuthDiagnostics.Stage.PERMISSION_RECHECK;
            if(!attempt.permitted.getAsBoolean())throw new IOException("AI role no longer available.");
            stage=AuthDiagnostics.Stage.CREDENTIAL_SAVE;
            synchronized(this) {
                if(!Objects.equals(generations.get(attempt.key),attempt.generation))throw new IOException("Sign-in cancelled.");
                accounts.put(attempt.key,account,true);unsafeRefresh.remove(attempt.key);
            }
            String status=account.canInvoke()?"ChatGPTへの接続が完了しました。このアプリのAI応答はChatGPTプランの枠を使用します。\n/gvd model でモデルを選んでください。\n利用枠: https://chatgpt.com/settings/usage"
                    :"ログインしましたが、ChatGPTプラン利用への同意がありません。AI応答は停止しています。";
            stage=AuthDiagnostics.Stage.CALLBACK_RESPONSE;
            System.err.printf("GVD_AUTH_CONNECTED plan_usage=%s%n",account.canInvoke());
            reply(exchange,200,"ChatGPT sign-in complete. Return to Discord.");safeResult(attempt,status);
        } catch(Exception failure) {
            AuthDiagnostics.failure(stage,failure,callbackError);
            reply(exchange,400,"ChatGPT sign-in failed. Return to Discord and run /gvd auth again.");
            if(attempt!=null)safeResult(attempt,"ChatGPTへの接続が完了しませんでした。同意・アカウント・Workspaceを確認し、/gvd auth を再実行してください。");
        }
    }
    private static void safeResult(Attempt a,String text){try{a.result.accept(text);}catch(RuntimeException ignored){}}
    private boolean validRequest(HttpExchange exchange,String path) {
        return "GET".equals(exchange.getRequestMethod())&&path.equals(exchange.getRequestURI().getPath())
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                &&("127.0.0.1:"+server.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host"));
    }
    private static Map<String,String> query(HttpExchange exchange) throws IOException {
        String raw=exchange.getRequestURI().getRawQuery();if(raw==null||raw.length()>16384)throw new IOException("Invalid callback query.");
        var map=new HashMap<String,String>();
        for(String item:raw.split("&")){String[] pair=item.split("=",2);String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            if(map.putIfAbsent(key,pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"")!=null)throw new IOException("Duplicate callback parameter.");}
        return map;
    }
    private static void headers(HttpExchange e){e.getResponseHeaders().set("Cache-Control","no-store");e.getResponseHeaders().set("Referrer-Policy","no-referrer");e.getResponseHeaders().set("X-Content-Type-Options","nosniff");e.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");}
    private static void reply(HttpExchange e,int status,String message) throws IOException {
        headers(e);byte[] bytes=message.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);try(var output=e.getResponseBody()){output.write(bytes);}e.close();
    }
    static ChatGptAccounts.Account fromTokens(JsonNode response,ChatGptAccounts.Account old) throws IOException {
        var a=old==null?new ChatGptAccounts.Account():old;
        if(!"Bearer".equalsIgnoreCase(response.path("token_type").asText())||response.path("access_token").asText().isBlank()
                ||response.path("refresh_token").asText().isBlank()||response.path("expires_in").asLong()<=0)throw new IOException("Incomplete OAuth response.");
        a.accessToken=response.path("access_token").asText();a.refreshToken=response.path("refresh_token").asText();
        a.scope=response.has("scope")?response.path("scope").asText():a.scope;
        a.idToken=response.has("id_token")?response.path("id_token").asText():a.idToken;
        a.expiresAt=Instant.now().getEpochSecond()+response.path("expires_in").asLong();return a;
    }
    synchronized ChatGptAccounts.Account ready(String user) throws IOException {
        String key=accounts.activeKey(user);var account=key==null?null:accounts.get(key);
        if(account==null||account.accessToken.isEmpty()||unsafeRefresh.contains(key))throw new ChatGptException("login_required",0);
        if(!account.canInvoke())throw new ChatGptException("permission_missing",0);
        if(account.expiresAt<Instant.now().plusSeconds(90).getEpochSecond()) {
            JsonNode response;
            try{response=transport.tokens(Map.of("grant_type","refresh_token","client_id",account.clientId,"refresh_token",account.refreshToken,"resource",OpenAiTransport.RESOURCE));}
            catch(ChatGptException e){
                if(Set.of("invalid_grant","invalid_refresh_token","token_expired","refresh_token_expired","refresh_token_invalidated","refresh_token_reused").contains(e.code))accounts.clearTokens(key);
                throw e;
            }
            // Persist the rotating token even if identity validation later fails; never retry the old refresh token.
            var updated=fromTokens(response,account);
            if(response.has("id_token"))try {
                var jwt=SignedJWT.parse(updated.idToken);
                OpenAiIdentity.verify(updated.idToken,updated.clientId,null,updated.subject,transport.keys(jwt.getHeader().getKeyID()),Instant.now());
            }catch(Exception e){updated.accessToken="";updated.idToken="";accounts.put(key,updated,false);throw new IOException("Refreshed identity validation failed.");}
            try { accounts.put(key,updated,false); }
            catch(IOException e) { unsafeRefresh.add(key);throw e; }
            account=updated;
        }
        if(!account.canInvoke())throw new ChatGptException("permission_missing",0);
        return account;
    }
    synchronized boolean isCurrent(String owner,ChatGptAccounts.Account expected) throws IOException {
        String key=accounts.activeKey(owner);var current=key==null?null:accounts.get(key);
        return current!=null&&!current.accessToken.isEmpty()&&current.clientId.equals(expected.clientId)&&current.subject.equals(expected.subject);
    }
    synchronized String logout(String user,String label) throws IOException {
        String key=ChatGptAccounts.key(user,label);var account=accounts.get(key);boolean revoked=true;
        attempts.values().removeIf(a->a.key.equals(key));generations.merge(key,1L,Long::sum);
        if(account!=null&&!account.refreshToken.isEmpty())try{transport.revoke(account);}catch(IOException e){revoked=false;}
        accounts.clearTokens(key);
        return revoked?"ログアウトしました。アカウントの登録情報とホストIDは次回ログイン用に保持しています。"
                :"ローカルではログアウトしましたが、OpenAI側の失効を確認できませんでした。ChatGPT設定からアプリ接続を解除してください。";
    }
    @Override public void close(){synchronized(this){attempts.clear();}server.stop(0);callbacks.shutdownNow();}
}
