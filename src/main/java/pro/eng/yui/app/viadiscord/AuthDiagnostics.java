package pro.eng.yui.app.viadiscord;

import java.util.Set;

/** Log only stages, typed validation reasons, and known protocol error codes. */
final class AuthDiagnostics {
    enum Stage {
        START_REQUEST, START_REDIRECT, CALLBACK_REQUEST, CALLBACK_QUERY, CALLBACK_STATE,
        CALLBACK_CONSENT, CALLBACK_CLIENT, CALLBACK_CODE, TOKEN_EXCHANGE, ID_TOKEN_PARSE,
        SIGNING_KEYS, ID_TOKEN_VERIFY, TOKEN_RESPONSE, PERMISSION_RECHECK, CREDENTIAL_SAVE,
        CALLBACK_RESPONSE, REGISTRATION_SAVE
    }
    private static final Set<String> OAUTH_CODES=Set.of("access_denied", "invalid_request", "invalid_client",
            "invalid_grant", "invalid_scope", "unauthorized_client", "unsupported_response_type",
            "unsupported_grant_type", "server_error", "temporarily_unavailable", "request_failed");
    static void failure(Stage stage,Throwable failure,String callbackError) {
        FailureDiagnostics.log("AUTH_"+stage,failure);
        String reason=failure instanceof OpenAiIdentity.ValidationFailure validation ? validation.reason.name() : "UNSPECIFIED";
        int status=failure instanceof ChatGptException upstream ? upstream.status : 0;
        String code=failure instanceof ChatGptException upstream ? upstream.code : callbackError;
        code=OAUTH_CODES.contains(code)?code:code.isEmpty()?"none":"unrecognized";
        String request=failure instanceof ChatGptException upstream ? upstream.requestId : "";
        if(!request.matches("[a-zA-Z0-9_-]{1,150}"))request="none";
        var hint=failure instanceof ChatGptException upstream ? upstream.descriptionHint : ChatGptException.DescriptionHint.NOT_PROVIDED;
        System.err.printf("GVD_AUTH_FAILED stage=%s reason=%s error_type=%s http_status=%d oauth_code=%s request_id=%s description_hint=%s%n",
                stage,reason,failure.getClass().getSimpleName(),status,code,request,hint);
    }
    private AuthDiagnostics() {}
}
