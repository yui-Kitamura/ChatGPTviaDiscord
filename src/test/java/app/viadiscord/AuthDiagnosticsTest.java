package app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AuthDiagnosticsTest {
    private static String capture(Runnable action) throws Exception {
        var previous=System.err;var bytes=new ByteArrayOutputStream();
        try(var output=new PrintStream(bytes,true,StandardCharsets.UTF_8)){
            System.setErr(output);action.run();return bytes.toString(StandardCharsets.UTF_8);
        }finally{System.setErr(previous);}
    }
    @Test void recordsStageWithoutExceptionMessageOrCallbackSecrets() throws Exception {
        String secret="secret-token-and-private-callback-state";
        String log=capture(()->AuthDiagnostics.failure(AuthDiagnostics.Stage.CREDENTIAL_SAVE,new IOException(secret),secret));
        assertTrue(log.contains("GVD_AUTH_FAILED stage=CREDENTIAL_SAVE"));
        assertTrue(log.contains("error_type=IOException"));assertTrue(log.contains("oauth_code=unrecognized"));
        assertFalse(log.contains(secret));
    }
    @Test void knownTokenErrorsKeepOnlySafeProtocolDiagnostics() throws Exception {
        var error=new ChatGptException("invalid_grant",400);error.requestId="req_test123";
        String log=capture(()->AuthDiagnostics.failure(AuthDiagnostics.Stage.TOKEN_EXCHANGE,error,""));
        assertTrue(log.contains("http_status=400 oauth_code=invalid_grant request_id=req_test123"));
    }
    @Test void identityRejectionsHaveSpecificReasonsWithoutClaims() throws Exception {
        String log=capture(()->AuthDiagnostics.failure(AuthDiagnostics.Stage.ID_TOKEN_VERIFY,new OpenAiIdentity.ValidationFailure(OpenAiIdentity.Reason.NONCE),""));
        assertTrue(log.contains("stage=ID_TOKEN_VERIFY reason=NONCE"));
    }
    @Test void arbitraryUpstreamCodesAndRequestIdsAreNotCopied() throws Exception {
        var error=new ChatGptException("private_token",400);error.requestId="private\nclaim";
        String log=capture(()->AuthDiagnostics.failure(AuthDiagnostics.Stage.TOKEN_EXCHANGE,error,""));
        assertTrue(log.contains("oauth_code=unrecognized request_id=none"));
        assertFalse(log.contains("private"));
    }
}
