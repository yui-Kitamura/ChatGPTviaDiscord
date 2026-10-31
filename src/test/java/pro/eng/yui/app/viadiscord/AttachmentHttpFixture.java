package pro.eng.yui.app.viadiscord;

import java.io.*;
import java.util.concurrent.*;
import net.dv8tion.jda.api.utils.FileProxy;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import okhttp3.*;
import org.junit.jupiter.api.extension.*;

/** Runs the real JDA downloader against in-memory original-URL responses. */
final class AttachmentHttpFixture implements BeforeAllCallback, AfterAllCallback {
    static final ConcurrentMap<String,NamedAttachmentProxy> sources=new ConcurrentHashMap<>();
    @Override public void beforeAll(ExtensionContext context) {
        FileProxy.setDefaultHttpClient(new OkHttpClient.Builder().addInterceptor(chain->{
            var source=sources.get(chain.request().url().toString());
            if(source==null)throw new IOException("Unexpected attachment URL");
            try(var input=source.download().get(5,TimeUnit.SECONDS)) {
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create(input.readAllBytes(),null)).build();
            } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            catch(ExecutionException|TimeoutException e){throw new IOException(e);}
        }).build());
    }
    @Override public void afterAll(ExtensionContext context) {
        sources.clear();
        FileProxy.setDefaultHttpClient(new OkHttpClient());
    }
}
