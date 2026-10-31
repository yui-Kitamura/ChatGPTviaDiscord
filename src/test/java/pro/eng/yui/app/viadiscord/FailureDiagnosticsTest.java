package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class FailureDiagnosticsTest {
    static String capture(Runnable action) {
        var previous=System.err;var bytes=new ByteArrayOutputStream();
        try(var output=new PrintStream(bytes,true,StandardCharsets.UTF_8)) {
            System.setErr(output);action.run();return bytes.toString(StandardCharsets.UTF_8);
        } finally {System.setErr(previous);}
    }
    @Test void retainsCauseTypesAndSitesWithoutMessagesOrSignedUrls() {
        var error=new CompletionException(new IOException("https://cdn.discordapp.com/private?signature=secret-token"));
        String log=capture(()->FailureDiagnostics.log("ATTACHMENT",error,"1566000639157473370"));
        assertTrue(log.contains("source_id=1566000639157473370"));
        assertTrue(log.contains("causes=CompletionException>IOException"));
        assertTrue(log.contains("FailureDiagnosticsTest.retainsCauseTypesAndSitesWithoutMessagesOrSignedUrls:"));
        assertFalse(log.contains("signature"));assertFalse(log.contains("secret-token"));assertFalse(log.contains("https://"));
    }
    @Test void discordFailureRecordsNumericProtocolFieldsOnly() {
        var upstream=org.mockito.Mockito.mock(net.dv8tion.jda.api.exceptions.ErrorResponseException.class);
        var response=org.mockito.Mockito.mock(net.dv8tion.jda.api.requests.Response.class);
        org.mockito.Mockito.when(upstream.getStackTrace()).thenReturn(new StackTraceElement[0]);
        org.mockito.Mockito.when(upstream.getErrorCode()).thenReturn(50013);
        org.mockito.Mockito.when(upstream.getResponse()).thenReturn(response);
        org.mockito.Mockito.when(upstream.getMessage()).thenReturn("private payload");
        String log=capture(()->FailureDiagnostics.log("COMMAND_REPLY",upstream,"123"));
        assertTrue(log.contains("discord_code=50013"));assertFalse(log.contains("private payload"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @SuppressWarnings("unchecked") void failedErrorReplyLogsInteractionForSyncAndAsyncFailures(boolean synchronous) {
        var hook=org.mockito.Mockito.mock(net.dv8tion.jda.api.interactions.InteractionHook.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(hook.getInteraction().getId()).thenReturn("1566000639157473370");
        var edit=org.mockito.Mockito.mock(net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction.class,org.mockito.Mockito.RETURNS_SELF);
        org.mockito.Mockito.when(hook.editOriginal("safe error")).thenReturn(edit);
        var failure=new IllegalStateException("private signed URL and token");
        if(synchronous)org.mockito.Mockito.when(hook.editOriginal("safe error")).thenThrow(failure);
        else org.mockito.Mockito.doAnswer(i->{i.<java.util.function.Consumer<Throwable>>getArgument(1).accept(failure);return null;})
                .when(edit).queue(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        String log=capture(()->FailureDiagnostics.edit(hook,"safe error","MODE_REPLY"));
        assertTrue(log.contains("operation=MODE_REPLY source_id=1566000639157473370"));
        assertTrue(log.contains("causes=IllegalStateException"));assertFalse(log.contains("private"));
    }
    @Test void nestedContextsRestoreAndDoNotLeakIntoNextOperation() {
        assertNull(FailureDiagnostics.sourceId());
        try(var first=FailureDiagnostics.context("123")) {
            try(var second=FailureDiagnostics.context("456")) {
                assertTrue(capture(()->FailureDiagnostics.log("INNER",null)).contains("source_id=456"));
            }
            assertTrue(capture(()->FailureDiagnostics.log("OUTER",null)).contains("source_id=123"));
        }
        assertNull(FailureDiagnostics.sourceId());
    }
    @Test void boundsCyclicCausesAndRejectsUntrustedContext() {
        var first=new IOException("private");var second=new IOException("private");
        first.initCause(second);second.initCause(first);
        String log=capture(()->FailureDiagnostics.log("bad\noperation",first,"private-token"));
        assertTrue(log.contains("operation=none source_id=none"));assertFalse(log.contains("private"));
        assertEquals(1,log.lines().count());assertTrue(log.length()<2000);
    }
}
