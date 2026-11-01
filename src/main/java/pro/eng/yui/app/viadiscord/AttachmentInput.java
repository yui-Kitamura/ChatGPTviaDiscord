package pro.eng.yui.app.viadiscord;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;

/** Inline bytes avoid relying on Discord's expiring signed URLs on later turns. */
final class AttachmentInput {
    static final int MAX_BYTES=10*1024*1024;
    private static final Map<String,String> IMAGES=Map.of("png","image/png","jpg","image/jpeg","jpeg","image/jpeg","webp","image/webp","gif","image/gif");
    private static final Set<String> DOCUMENTS=Set.of("pdf","doc","docx","dot","odt","rtf","pages","ppt","pptx","pot","ppa","pps","pwz","wiz","key","xls","xlsx","xla","xlb","xlc","xlm","xlt","xlw","csv","tsv","iif");
    private static final Set<String> TEXT=Set.of("txt","text","md","markdown","json","jsonl","xml","html","htm","css","js","mjs","ts","tsx","jsx","java","cs","py","c","cc","cpp","cxx","h","hh","go","rs","sql","sh","bat","ps1","yaml","yml","toml","ini","conf","properties","log","rst","srt","vtt","eml","ics","vcf","tex","rb","php","swift","kt","lua","r");
    static final class Unavailable extends IOException implements FailureDiagnostics.Classified {
        private final FailureDiagnostics.Details details;
        Unavailable(String message,FailureDiagnostics.Reason reason,Message.Attachment attachment,long actual,Throwable cause) {
            this(message,reason,attachment,actual,cause,-1);
        }
        Unavailable(String message,FailureDiagnostics.Reason reason,Message.Attachment attachment,long actual,Throwable cause,long expected) {
            super(message,cause);
            details=new FailureDiagnostics.Details(reason,attachment.getId(),attachment.getSize(),actual,expected);
        }
        @Override public FailureDiagnostics.Details details(){return details;}
    }

    static Object content(String text,List<Message.Attachment> attachments) throws IOException {
        if(attachments.isEmpty())return text;
        long declared=0;
        for(var attachment:attachments) {
            String ext=extension(attachment);
            if(!IMAGES.containsKey(ext)&&!DOCUMENTS.contains(ext)&&!TEXT.contains(ext))
                throw new Unavailable("未対応形式の添付があります。画像（PNG/JPEG/WebP/GIF）、PDF、Office文書、テキスト形式で投稿してください。AIへの送信は行っていません。",FailureDiagnostics.Reason.UNSUPPORTED_FORMAT,attachment,-1,null);
            declared+=attachment.getSize();
            if(attachment.getSize()<0||declared>MAX_BYTES)throw tooLarge(attachment,-1);
        }
        var parts=new ArrayList<Map<String,String>>();
        if(!text.isBlank())parts.add(Map.of("type","input_text","text",text));
        int total=0;
        for(var attachment:attachments) {
            byte[] bytes=download(attachment,MAX_BYTES-total);
            total+=bytes.length;
            if(bytes.length==0)throw new Unavailable("空の添付ファイルがあります。ファイルを確認して再投稿してください。AIへの送信は行っていません。",FailureDiagnostics.Reason.EMPTY_ATTACHMENT,attachment,0,null);
            String ext=extension(attachment);
            String mime=IMAGES.get(ext);
            if(mime==null) {
                mime=attachment.getContentType();
                if(mime==null||!mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))mime="application/octet-stream";
                if(ext.equals("pdf"))mime="application/pdf";
                if(TEXT.contains(ext))mime="text/plain";
            }
            String data="data:"+mime+";base64,"+Base64.getEncoder().encodeToString(bytes);
            if(IMAGES.containsKey(ext))parts.add(Map.of("type","input_image","image_url",data));
            else parts.add(Map.of("type","input_file","filename",attachment.getFileName(),"file_data",data));
        }
        return parts;
    }
    private static String extension(Message.Attachment attachment) {
        String name=attachment.getFileName();int dot=name.lastIndexOf('.');
        return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
    }
    private static Unavailable tooLarge(Message.Attachment attachment,long actual){return new Unavailable("添付の合計は1投稿10 MiBまでです。ファイルを分割・縮小して再投稿してください。AIへの送信は行っていません。",FailureDiagnostics.Reason.SIZE_LIMIT,attachment,actual,null);}
    /** Captures the HTTP entity size before JDA decodes Content-Encoding. */
    private static final class OriginalDownload extends NamedAttachmentProxy {
        private final java.util.concurrent.atomic.AtomicLong expectedBytes=new java.util.concurrent.atomic.AtomicLong(-1);
        OriginalDownload(Message.Attachment attachment){super(attachment.getUrl(),attachment.getFileName());}
        @Override protected okhttp3.OkHttpClient getHttpClient() {
            var builder=super.getHttpClient().newBuilder();
            // Observe the final response after the shared client's interceptors have run.
            builder.interceptors().add(0,chain->{
                var response=chain.proceed(chain.request());
                if(response.code()!=200||response.header("Content-Range")!=null) {
                    int status=response.code();response.close();
                    throw new IOException("Attachment HTTP response is not a complete entity (status "+status+").");
                }
                String encoding=response.header("Content-Encoding");
                // A compressed entity length cannot be compared to JDA's decoded stream.
                long length=-1;
                if(encoding==null||encoding.equalsIgnoreCase("identity")) {
                    String header=response.header("Content-Length");
                    if(header!=null) {
                        try{length=Long.parseLong(header);}catch(NumberFormatException ignored){}
                    } else if(response.body()!=null)length=response.body().contentLength();
                }
                expectedBytes.set(length<0?-1:length);
                return response;
            });
            return builder.build();
        }
    }
    private static byte[] download(Message.Attachment attachment,int remaining) throws IOException {
        OriginalDownload source;
        CompletableFuture<InputStream> future;
        try {source=new OriginalDownload(attachment);future=source.download();}
        catch(RuntimeException error){throw unavailable(attachment,FailureDiagnostics.Reason.DOWNLOAD_FAILED,-1,error);}
        try {
            try(var input=future.get(30,TimeUnit.SECONDS);
                var timer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
                var timedOut=new java.util.concurrent.atomic.AtomicBoolean();
                var stop=timer.schedule(()->{timedOut.set(true);try{input.close();}catch(IOException ignored){}},30,TimeUnit.SECONDS);
                try {
                    byte[] bytes=input.readNBytes(remaining+1);
                    if(timedOut.get())throw unavailable(attachment,FailureDiagnostics.Reason.READ_TIMEOUT,bytes.length,null);
                    if(bytes.length>remaining)throw tooLarge(attachment,bytes.length);
                    long expected=source.expectedBytes.get();
                    if(expected>=0&&bytes.length!=expected)
                        throw new Unavailable("添付ファイルを取得できませんでした。添付を再投稿してください。AIへの送信は行っていません。",
                                FailureDiagnostics.Reason.SIZE_MISMATCH,attachment,bytes.length,null,expected);
                    if(bytes.length!=attachment.getSize())
                        FailureDiagnostics.attachmentSizeDifference(attachment.getId(),attachment.getSize(),bytes.length,expected);
                    return bytes;
                } catch(Unavailable error){throw error;}
                catch(IOException error){throw unavailable(attachment,timedOut.get()?FailureDiagnostics.Reason.READ_TIMEOUT:FailureDiagnostics.Reason.DOWNLOAD_FAILED,-1,error);}
                finally {stop.cancel(false);}
            }
        } catch(Unavailable error){throw error;}
        catch(InterruptedException error){Thread.currentThread().interrupt();closeWhenReady(future);throw unavailable(attachment,FailureDiagnostics.Reason.DOWNLOAD_INTERRUPTED,-1,error);}
        catch(TimeoutException error){closeWhenReady(future);throw unavailable(attachment,FailureDiagnostics.Reason.DOWNLOAD_TIMEOUT,-1,error);}
        catch(ExecutionException|IOException error){throw unavailable(attachment,FailureDiagnostics.Reason.DOWNLOAD_FAILED,-1,error);}
    }
    private static void closeWhenReady(CompletableFuture<InputStream> future) {
        future.thenAccept(input->{try{input.close();}catch(IOException ignored){}});
    }
    private static Unavailable unavailable(Message.Attachment attachment,FailureDiagnostics.Reason reason,long actual,Throwable cause){return new Unavailable("添付ファイルを取得できませんでした。添付を再投稿してください。AIへの送信は行っていません。",reason,attachment,actual,cause);}
}
