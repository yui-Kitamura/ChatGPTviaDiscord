package app.viadiscord;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.time.Instant;

final class OpenAiIdentity {
    record Identity(String subject,String email) {}
    static Identity verify(String token,String clientId,String nonce,String expectedSubject,JWKSet keys,Instant now) throws IOException {
        try {
            SignedJWT jwt=SignedJWT.parse(token);
            if(!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()))throw new Exception();
            var key=keys.getKeyByKeyId(jwt.getHeader().getKeyID());
            if(!(key instanceof RSAKey rsa) || !jwt.verify(new RSASSAVerifier(rsa)))throw new Exception();
            var c=jwt.getJWTClaimsSet();
            if(!"https://auth.openai.com".equals(c.getIssuer()) || !c.getAudience().contains(clientId)
                    || (c.getAudience().size()>1 && !clientId.equals(c.getStringClaim("azp")))
                    || c.getExpirationTime()==null || c.getExpirationTime().toInstant().isBefore(now.minusSeconds(5))
                    || c.getIssueTime()==null || c.getIssueTime().toInstant().isAfter(now.plusSeconds(5))
                    || (c.getNotBeforeTime()!=null && c.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(5)))
                    || c.getSubject()==null || c.getSubject().isBlank()
                    || (nonce!=null&&!nonce.equals(c.getStringClaim("nonce")))
                    || (expectedSubject!=null&&!expectedSubject.equals(c.getSubject())))throw new Exception();
            return new Identity(c.getSubject(),c.getStringClaim("email"));
        } catch(Exception e) {throw new IOException("ChatGPT ID token validation failed.");}
    }
    private OpenAiIdentity() {}
}
