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
    static final class Unavailable extends IOException {
        Unavailable(String message){super(message);}
    }

    static Object content(String text,List<Message.Attachment> attachments) throws IOException {
        if(attachments.isEmpty())return text;
        long declared=0;
        for(var attachment:attachments) {
            String ext=extension(attachment);
            if(!IMAGES.containsKey(ext)&&!DOCUMENTS.contains(ext)&&!TEXT.contains(ext))
                throw new Unavailable("未対応形式の添付があります。画像（PNG/JPEG/WebP/GIF）、PDF、Office文書、テキスト形式で投稿してください。AIへの送信は行っていません。");
            declared+=attachment.getSize();
            if(attachment.getSize()<0||declared>MAX_BYTES)throw tooLarge();
        }
        var parts=new ArrayList<Map<String,String>>();
        if(!text.isBlank())parts.add(Map.of("type","input_text","text",text));
        int total=0;
        for(var attachment:attachments) {
            byte[] bytes=download(attachment,MAX_BYTES-total);
            total+=bytes.length;
            if(bytes.length==0)throw new Unavailable("空の添付ファイルがあります。ファイルを確認して再投稿してください。AIへの送信は行っていません。");
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
    private static Unavailable tooLarge(){return new Unavailable("添付の合計は1投稿10 MiBまでです。ファイルを分割・縮小して再投稿してください。AIへの送信は行っていません。");}
    private static byte[] download(Message.Attachment attachment,int remaining) throws IOException {
        // Metadata size describes the original, not Discord's transformed image proxy.
        var future=new NamedAttachmentProxy(attachment.getUrl(),attachment.getFileName()).download();
        try {
            try(var input=future.get(30,TimeUnit.SECONDS);
                var timer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
                var stop=timer.schedule(()->{try{input.close();}catch(IOException ignored){}},30,TimeUnit.SECONDS);
                try {
                    byte[] bytes=input.readNBytes(remaining+1);
                    if(bytes.length>remaining)throw tooLarge();
                    if(bytes.length!=attachment.getSize())throw unavailable();
                    return bytes;
                } finally {stop.cancel(false);}
            }
        } catch(Unavailable error){throw error;}
        catch(InterruptedException error){Thread.currentThread().interrupt();closeWhenReady(future);throw unavailable();}
        catch(TimeoutException error){closeWhenReady(future);throw unavailable();}
        catch(ExecutionException|IOException error){throw unavailable();}
    }
    private static void closeWhenReady(CompletableFuture<InputStream> future) {
        future.thenAccept(input->{try{input.close();}catch(IOException ignored){}});
    }
    private static Unavailable unavailable(){return new Unavailable("添付ファイルを取得できませんでした。添付を再投稿してください。AIへの送信は行っていません。");}
}
