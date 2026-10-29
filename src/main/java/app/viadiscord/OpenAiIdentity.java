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
    enum Reason { ALGORITHM, SIGNING_KEY, SIGNATURE, ISSUER, AUDIENCE, EXPIRATION, ISSUED_AT, NOT_BEFORE, SUBJECT, NONCE, ACCOUNT, MALFORMED }
    static final class ValidationFailure extends IOException {
        final Reason reason;
        ValidationFailure(Reason reason){super("ChatGPT identity validation failed: "+reason);this.reason=reason;}
    }
    static Identity verify(String token,String clientId,String nonce,String expectedSubject,JWKSet keys,Instant now) throws IOException {
        try {
            SignedJWT jwt=SignedJWT.parse(token);
            if(!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()))throw new ValidationFailure(Reason.ALGORITHM);
            var key=keys.getKeyByKeyId(jwt.getHeader().getKeyID());
            if(!(key instanceof RSAKey rsa))throw new ValidationFailure(Reason.SIGNING_KEY);
            if(!jwt.verify(new RSASSAVerifier(rsa)))throw new ValidationFailure(Reason.SIGNATURE);
            var c=jwt.getJWTClaimsSet();
            if(!"https://auth.openai.com".equals(c.getIssuer()))throw new ValidationFailure(Reason.ISSUER);
            if(!c.getAudience().contains(clientId)||(c.getAudience().size()>1&&!clientId.equals(c.getStringClaim("azp"))))throw new ValidationFailure(Reason.AUDIENCE);
            if(c.getExpirationTime()==null||c.getExpirationTime().toInstant().isBefore(now.minusSeconds(5)))throw new ValidationFailure(Reason.EXPIRATION);
            if(c.getIssueTime()==null||c.getIssueTime().toInstant().isAfter(now.plusSeconds(5)))throw new ValidationFailure(Reason.ISSUED_AT);
            if(c.getNotBeforeTime()!=null&&c.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(5)))throw new ValidationFailure(Reason.NOT_BEFORE);
            if(c.getSubject()==null||c.getSubject().isBlank())throw new ValidationFailure(Reason.SUBJECT);
            if(nonce!=null&&!nonce.equals(c.getStringClaim("nonce")))throw new ValidationFailure(Reason.NONCE);
            if(expectedSubject!=null&&!expectedSubject.equals(c.getSubject()))throw new ValidationFailure(Reason.ACCOUNT);
            return new Identity(c.getSubject(),c.getStringClaim("email"));
        } catch(ValidationFailure failure) {throw failure;}
        catch(Exception e) {throw new ValidationFailure(Reason.MALFORMED);}
    }
    private OpenAiIdentity() {}
}
