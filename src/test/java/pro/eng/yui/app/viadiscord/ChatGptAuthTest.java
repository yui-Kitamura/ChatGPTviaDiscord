package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatGptAuthTest {
    static final String USER="123456789012345678", CLIENT="oaiapp_test";
    @TempDir Path directory;
    final HttpClient browser=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static class Fake extends OpenAiTransport {
        final RSAKey key;
        String nonce="", scopes="openid chatgpt.tokens.use.direct offline_access", subject="owner";
        int exchanges,refreshes,revocations; Map<String,String> fields;
        ChatGptException fail;
        Fake() throws Exception {super(Duration.ofSeconds(5));key=new RSAKeyGenerator(2048).keyID("test").generate();}
        @Override synchronized JsonNode tokens(Map<String,String> fields) throws IOException {
            this.fields=fields;
            if(fail!=null)throw fail;
            if("refresh_token".equals(fields.get("grant_type")))refreshes++;else exchanges++;
            try {
                return ChatGptAccounts.JSON.valueToTree(Map.of("access_token","access-"+refreshes,"refresh_token","refresh-"+refreshes,
                        "id_token",signed(nonce,CLIENT,subject,"https://auth.openai.com",Instant.now().plusSeconds(3600)),
                        "scope",scopes,"token_type","Bearer","expires_in",3600));
            }catch(Exception e){throw new IOException("Test signing failed.");}
        }
        String signed(String nonce,String audience,String subject,String issuer,Instant expiry) throws Exception {
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),new JWTClaimsSet.Builder()
                    .issuer(issuer).audience(audience).subject(subject).issueTime(new Date()).expirationTime(Date.from(expiry)).claim("nonce",nonce).claim("email","owner@example.test").build());
            jwt.sign(new RSASSASigner(key));return jwt.serialize();
        }
        @Override synchronized JWKSet keys(String kid){return new JWKSet(key.toPublicJWK());}
        @Override void revoke(ChatGptAccounts.Account account){revocations++;}
    }
    static Map<String,String> query(URI uri) {
        var result=new HashMap<String,String>();
        for(String item:uri.getRawQuery().split("&")){var pair=item.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}
        return result;
    }
    URI authorize(String start,Fake fake) throws Exception {
        var redirect=browser.send(HttpRequest.newBuilder(URI.create(start)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(302,redirect.statusCode());URI uri=URI.create(redirect.headers().firstValue("Location").orElseThrow());
        fake.nonce=query(uri).get("nonce");return uri;
    }
    HttpResponse<String> callback(URI authorization,String state,String client) throws Exception {
        String callback=query(authorization).get("redirect_uri");
        return browser.send(HttpRequest.newBuilder(URI.create(callback+"?"+OpenAiTransport.form(Map.of("state",state,"code","one-use-code","client_id",client)))).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void dynamicRegistrationUsesPkceValidatesCallbackAndPersistsHost() throws Exception {
        var fake=new Fake();String host;
        try(var accounts=new ChatGptAccounts(directory);var session=new ChatGptSession(accounts,fake,0)) {
            host=accounts.hostId();var result=new AtomicReference<String>();
            URI authorization=authorize(session.login(USER,"personal",()->true,result::set),fake);var values=query(authorization);
            assertEquals("dynamic_agent_client",values.get("client_id"));assertEquals("ChatGPTviaDiscord",values.get("agent_name_hint"));
            assertEquals("S256",values.get("code_challenge_method"));assertEquals(host,values.get("ext_agent_host_id"));
            assertTrue(values.get("redirect_uri").startsWith("http://127.0.0.1:"));
            assertEquals(400,callback(authorization,"wrong-state",CLIENT).statusCode());assertEquals(0,fake.exchanges);
            var success=callback(authorization,values.get("state"),CLIENT);assertEquals(200,success.statusCode());
            assertFalse(success.body().contains("access-"));assertEquals(1,fake.exchanges);
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(fake.fields.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
            assertEquals(values.get("code_challenge"),challenge);assertEquals(CLIENT,fake.fields.get("client_id"));
            assertEquals("owner",session.ready(USER).subject);assertTrue(result.get().contains("ChatGPTプラン"));
            assertEquals(400,callback(authorization,values.get("state"),CLIENT).statusCode());assertEquals(1,fake.exchanges);
            var returning=query(authorize(session.login(USER,"personal",()->true,result::set),fake));
            assertEquals(CLIENT,returning.get("client_id"));assertFalse(returning.containsKey("agent_name_hint"));assertTrue(returning.containsKey("id_token_hint"));
        }
        try(var reopened=new ChatGptAccounts(directory)){assertEquals(host,reopened.hostId());assertEquals(CLIENT,reopened.get(ChatGptAccounts.key(USER,"personal")).clientId);}
        if(Files.getFileStore(directory).supportsFileAttributeView("posix"))assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("accounts.json"))));
    }
    @Test void wrongNonceAndRevokedRoleCannotReplaceActiveAccount() throws Exception {
        var fake=new Fake();var permitted=new AtomicBoolean(true);
        try(var accounts=new ChatGptAccounts(directory);var session=new ChatGptSession(accounts,fake,0)) {
            var auth=authorize(session.login(USER,"personal",permitted::get,t->{}),fake);
            fake.nonce="wrong";assertEquals(400,callback(auth,query(auth).get("state"),CLIENT).statusCode());assertNull(accounts.activeKey(USER));
            auth=authorize(session.login(USER,"personal",permitted::get,t->{}),fake);permitted.set(false);
            assertEquals(400,callback(auth,query(auth).get("state"),CLIENT).statusCode());assertEquals(1,fake.exchanges);
        }
    }
    @Test void missingScopeStoresIdentityButBlocksInference() throws Exception {
        var fake=new Fake();fake.scopes="openid email";
        try(var accounts=new ChatGptAccounts(directory);var session=new ChatGptSession(accounts,fake,0)) {
            var auth=authorize(session.login(USER,"default",()->true,t->{}),fake);
            assertEquals(200,callback(auth,query(auth).get("state"),CLIENT).statusCode());
            assertEquals("permission_missing",assertThrows(ChatGptException.class,()->session.ready(USER)).code);
            var retry=query(authorize(session.login(USER,"default",()->true,t->{}),fake));
            assertEquals("consent",retry.get("prompt"));
        }
    }
    @Test void rotatingRefreshIsSerializedAndLogoutRetainsRegistration() throws Exception {
        var fake=new Fake();
        try(var accounts=new ChatGptAccounts(directory);var session=new ChatGptSession(accounts,fake,0)) {
            var a=new ChatGptAccounts.Account();a.clientId=CLIENT;a.subject="owner";a.accessToken="expired";a.refreshToken="old";a.scope=fake.scopes;a.expiresAt=0;
            accounts.put(ChatGptAccounts.key(USER,"default"),a,true);
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
                var jobs=new ArrayList<Future<String>>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->session.ready(USER).accessToken));
                for(var job:jobs)assertEquals("access-1",job.get());
            }
            assertEquals(1,fake.refreshes);assertEquals("refresh-1",accounts.get(accounts.activeKey(USER)).refreshToken);
            session.logout(USER,"default");assertEquals(1,fake.revocations);
            a=accounts.get(accounts.activeKey(USER));assertEquals(CLIENT,a.clientId);assertEquals("",a.refreshToken);assertEquals("",a.idToken);
        }
    }
    @Test void terminalRefreshClearsTokensButTemporaryFailurePreservesThem() throws Exception {
        var fake=new Fake();
        try(var accounts=new ChatGptAccounts(directory);var session=new ChatGptSession(accounts,fake,0)) {
            var a=new ChatGptAccounts.Account();a.clientId=CLIENT;a.accessToken="expired";a.refreshToken="old";a.scope=fake.scopes;
            accounts.put(ChatGptAccounts.key(USER,"default"),a,true);
            fake.fail=new ChatGptException("subscription_sharing_usage_unavailable",503);
            assertThrows(ChatGptException.class,()->session.ready(USER));assertEquals("old",accounts.get(accounts.activeKey(USER)).refreshToken);
            fake.fail=new ChatGptException("invalid_grant",400);
            assertThrows(ChatGptException.class,()->session.ready(USER));assertEquals("",accounts.get(accounts.activeKey(USER)).refreshToken);
        }
    }
    @Test void identityChecksRejectSignatureAudienceIssuerExpiryAndSubject() throws Exception {
        var f=new Fake();var valid=f.signed("nonce",CLIENT,"owner","https://auth.openai.com",Instant.now().plusSeconds(3600));
        assertEquals("owner",OpenAiIdentity.verify(valid,CLIENT,"nonce","owner",f.keys("test"),Instant.now()).subject());
        assertThrows(IOException.class,()->OpenAiIdentity.verify(valid,"other","nonce",null,f.keys("test"),Instant.now()));
        assertThrows(IOException.class,()->OpenAiIdentity.verify(valid,CLIENT,"wrong",null,f.keys("test"),Instant.now()));
        assertThrows(IOException.class,()->OpenAiIdentity.verify(valid,CLIENT,"nonce","other",f.keys("test"),Instant.now()));
        var other=new Fake();assertThrows(IOException.class,()->OpenAiIdentity.verify(valid,CLIENT,"nonce",null,other.keys("test"),Instant.now()));
        var expired=f.signed("nonce",CLIENT,"owner","https://auth.openai.com",Instant.now().minusSeconds(30));
        assertThrows(IOException.class,()->OpenAiIdentity.verify(expired,CLIENT,"nonce",null,f.keys("test"),Instant.now()));
        var issuer=f.signed("nonce",CLIENT,"owner","https://evil.example",Instant.now().plusSeconds(3600));
        assertThrows(IOException.class,()->OpenAiIdentity.verify(issuer,CLIENT,"nonce",null,f.keys("test"),Instant.now()));
    }
}
