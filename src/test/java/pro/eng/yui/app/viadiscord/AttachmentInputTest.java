package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.extension.ExtendWith(AttachmentHttpFixture.class)
class AttachmentInputTest {
    @TempDir Path directory;
    static Message.Attachment attachment(String name,String mime,byte[] bytes) {
        var attachment=mock(Message.Attachment.class);var proxy=mock(NamedAttachmentProxy.class);
        when(attachment.getId()).thenReturn("1566000639157473371");
        when(attachment.getFileName()).thenReturn(name);when(attachment.getContentType()).thenReturn(mime);
        when(attachment.getSize()).thenReturn(bytes.length);when(attachment.getProxy()).thenReturn(proxy);
        String url="https://cdn.discordapp.com/attachments/test/"+UUID.randomUUID()+"/"+name;
        when(attachment.getUrl()).thenReturn(url);
        when(attachment.getProxyUrl()).thenReturn("https://media.discordapp.net/transformed/"+name);
        AttachmentHttpFixture.sources.put(url,proxy);
        when(proxy.download()).thenAnswer(i->CompletableFuture.completedFuture(new ByteArrayInputStream(bytes)));
        return attachment;
    }
    @Test void mixedTextImageAndDocumentCarryExactBytesAndSurviveHistoryReopen() throws Exception {
        var image=attachment("photo.PNG","image/png",new byte[]{1,2,3});
        var pdf=attachment("report.pdf","application/pdf",new byte[]{4,5,6});
        Object content=AttachmentInput.content("見て",List.of(image,pdf));
        var json=ChatGptAccounts.JSON.valueToTree(content);
        assertEquals("見て",json.get(0).path("text").asText());
        assertEquals("input_image",json.get(1).path("type").asText());
        assertEquals("data:image/png;base64,AQID",json.get(1).path("image_url").asText());
        assertEquals("input_file",json.get(2).path("type").asText());
        assertEquals("report.pdf",json.get(2).path("filename").asText());
        assertEquals("data:application/pdf;base64,BAUG",json.get(2).path("file_data").asText());
        var history=new ChatHistory(directory);var saved=new ChatHistory.History();
        saved.turns.add(Map.of("role","user","content",content));history.save("123",saved);
        assertEquals(saved.turns,history.load("123").turns);
    }
    @Test void imageOnlyAndTextFileOnlyNeedNoBody() throws Exception {
        var image=ChatGptAccounts.JSON.valueToTree(AttachmentInput.content("",List.of(attachment("a.jpg",null,new byte[]{1}))));
        assertEquals(1,image.size());assertEquals("input_image",image.get(0).path("type").asText());
        var text=ChatGptAccounts.JSON.valueToTree(AttachmentInput.content("",List.of(attachment("notes.txt",null,new byte[]{65}))));
        assertEquals("data:text/plain;base64,QQ==",text.get(0).path("file_data").asText());
    }
    @Test void unsupportedOrOversizedSubmissionNeverDownloads() {
        var image=attachment("ok.png","image/png",new byte[]{1});var zip=attachment("a.zip",null,new byte[]{2});
        assertThrows(AttachmentInput.Unavailable.class,()->AttachmentInput.content("question",List.of(image,zip)));
        verify(image,never()).getProxy();verify(zip,never()).getProxy();
        when(image.getSize()).thenReturn(AttachmentInput.MAX_BYTES+1);
        assertThrows(AttachmentInput.Unavailable.class,()->AttachmentInput.content("",List.of(image)));
        verify(image,never()).getProxy();
    }
    @Test void actualDownloadSizeIsBoundedEvenIfMetadataUnderstatesIt() {
        var image=attachment("a.png","image/png",new byte[AttachmentInput.MAX_BYTES+1]);
        when(image.getSize()).thenReturn(1);
        assertThrows(AttachmentInput.Unavailable.class,()->AttachmentInput.content("",List.of(image)));
    }
    @Test void downloadFailureDoesNotExposeUpstreamDetails() {
        var image=attachment("a.png","image/png",new byte[]{1});
        when(image.getProxy().download()).thenReturn(CompletableFuture.failedFuture(new IOException("secret signed URL")));
        var error=assertThrows(AttachmentInput.Unavailable.class,()->AttachmentInput.content("",List.of(image)));
        assertNotNull(error.getCause());
        assertEquals(FailureDiagnostics.Reason.DOWNLOAD_FAILED,error.details().reason());
        assertFalse(FailureDiagnosticsTest.capture(()->FailureDiagnostics.log("ATTACHMENT",error)).contains("secret"));
        assertFalse(error.getMessage().contains("secret"));assertTrue(error.getMessage().contains("AIへの送信は行っていません"));
    }
    @Test void imageUsesOriginalEvenWhenProxySizeWouldDiffer() throws Exception {
        var image=attachment("phone.jpg","image/jpeg",new byte[]{1,2,3,4});
        when(image.getProxy()).thenThrow(new AssertionError("Transformed image proxy must not be used"));
        var json=ChatGptAccounts.JSON.valueToTree(AttachmentInput.content("",List.of(image)));
        assertEquals("data:image/jpeg;base64,AQIDBA==",json.get(0).path("image_url").asText());
        verify(image,never()).getProxy();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={2,5})
    void responseLengthMismatchStillRejectsSubmission(long expected) {
        var image=attachment("phone.jpg","image/jpeg",new byte[]{1,2,3});
        when(image.getSize()).thenReturn(4);
        AttachmentHttpFixture.lengths.put(image.getUrl(),expected);
        var error=assertThrows(AttachmentInput.Unavailable.class,()->AttachmentInput.content("",List.of(image)));
        assertEquals(FailureDiagnostics.Reason.SIZE_MISMATCH,error.details().reason());
        String log=FailureDiagnosticsTest.capture(()->FailureDiagnostics.log("ATTACHMENT",error,"1566000639157473370"));
        assertTrue(log.contains("reason=SIZE_MISMATCH target=1566000639157473371 declared_bytes=4 actual_bytes=3"));
        assertTrue(log.contains("expected_bytes="+expected));
        assertFalse(log.contains("phone.jpg"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"197778,242093","242093,197778"})
    void metadataDifferenceAcceptsCompleteHttpEntity(int declared,int actual) throws Exception {
        var image=attachment("pc.jpg","image/jpeg",new byte[actual]);
        when(image.getSize()).thenReturn(declared);
        AttachmentHttpFixture.lengths.put(image.getUrl(),(long)actual);
        String log=FailureDiagnosticsTest.capture(()->{
            try {assertFalse(((List<?>)AttachmentInput.content("",List.of(image))).isEmpty());}
            catch(IOException e){throw new AssertionError(e);}
        });
        assertTrue(log.contains("GVD_ATTACHMENT_SIZE_DIFFERENCE"));
        assertTrue(log.contains("declared_bytes="+declared+" actual_bytes="+actual+" expected_bytes="+actual));
        assertFalse(log.contains("GVD_FAILURE"));assertFalse(log.contains("pc.jpg"));
    }
    @Test void gzipLengthIsNotComparedToDecodedStreamSize() throws Exception {
        byte[] original=new byte[242093];
        var output=new ByteArrayOutputStream();
        try(var gzip=new java.util.zip.GZIPOutputStream(output)){gzip.write(original);}
        byte[] encoded=output.toByteArray();
        var image=attachment("pc.jpg","image/jpeg",encoded);
        when(image.getSize()).thenReturn(197778);
        AttachmentHttpFixture.lengths.put(image.getUrl(),(long)encoded.length);
        AttachmentHttpFixture.encodings.put(image.getUrl(),"gzip");
        var json=ChatGptAccounts.JSON.valueToTree(AttachmentInput.content("",List.of(image)));
        String data=json.get(0).path("image_url").asText();
        assertArrayEquals(original,Base64.getDecoder().decode(data.substring(data.indexOf(',')+1)));
    }
    @Test void partialHttpResponseCannotBecomeSuccessfulImage() {
        var image=attachment("pc.jpg","image/jpeg",new byte[]{1,2,3});
        AttachmentHttpFixture.statuses.put(image.getUrl(),206);
        var error=assertThrows(AttachmentInput.Unavailable.class,()->AttachmentInput.content("",List.of(image)));
        assertEquals(FailureDiagnostics.Reason.DOWNLOAD_FAILED,error.details().reason());
    }
    @Test void realHttpTruncationRemainsDownloadFailure() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/photo.jpg",exchange->{
            exchange.sendResponseHeaders(200,20);
            try(var body=exchange.getResponseBody()){body.write(new byte[]{1,2,3});}
            catch(IOException ignored){}finally{exchange.close();}
        });server.start();
        // Exercise the actual HTTP transport instead of the in-memory fixture.
        net.dv8tion.jda.api.utils.FileProxy.setDefaultHttpClient(new okhttp3.OkHttpClient.Builder().readTimeout(1,java.util.concurrent.TimeUnit.SECONDS).build());
        try {
            var image=attachment("photo.jpg","image/jpeg",new byte[]{1,2,3});
            when(image.getUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort()+"/photo.jpg");
            var error=assertThrows(AttachmentInput.Unavailable.class,()->AttachmentInput.content("",List.of(image)));
            assertEquals(FailureDiagnostics.Reason.DOWNLOAD_FAILED,error.details().reason());assertNotNull(error.getCause());
        } finally {server.stop(0);new AttachmentHttpFixture().beforeAll(null);}
    }
    @Test void historyBudgetDropsOldInlineAttachmentsBeforeNextRequest() throws Exception {
        var turns=new ArrayList<Map<String,Object>>();
        for(int i=0;i<2;i++) {
            turns.add(Map.of("role","user","content",List.of(Map.of("type","input_image","image_url","x".repeat(9*1024*1024)))));
            turns.add(Map.of("role","assistant","content","answer"));
        }
        turns.add(Map.of("role","user","content","follow up"));
        ChatHistory.trim(turns);
        assertEquals(3,turns.size());assertEquals("user",turns.getFirst().get("role"));
        assertEquals("follow up",turns.getLast().get("content"));
    }
}
