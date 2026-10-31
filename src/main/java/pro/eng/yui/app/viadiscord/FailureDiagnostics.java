package pro.eng.yui.app.viadiscord;

import java.util.*;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

/** Bounded structural diagnostics. Never prints exception messages, bodies, filenames or URLs. */
final class FailureDiagnostics {
    enum Reason { UNSPECIFIED, UNSUPPORTED_FORMAT, SIZE_LIMIT, EMPTY_ATTACHMENT, SIZE_MISMATCH,
        DOWNLOAD_INTERRUPTED, DOWNLOAD_TIMEOUT, READ_TIMEOUT, DOWNLOAD_FAILED,
        CHANNEL_COUNT, MESSAGE_LIMIT, CHARACTER_LIMIT, EMPTY_DOCUMENT, FETCH_FAILED,
        CHANNEL_UNAVAILABLE, PERMISSION_DENIED, THREAD_MEMBERSHIP, HISTORY_EMPTY }
    record Details(Reason reason,String target,long declaredBytes,long actualBytes,long expectedBytes) {
        Details(Reason reason,String target,long declaredBytes,long actualBytes){this(reason,target,declaredBytes,actualBytes,-1);}
        Details(Reason reason,String target) {this(reason,target,-1,-1);}
    }
    interface Classified {Details details();}

    private static final ThreadLocal<String> SOURCE=new ThreadLocal<>();
    static String sourceId(){return SOURCE.get();}
    static Context context(String sourceId){return new Context(sourceId);}
    static final class Context implements AutoCloseable {
        private final String previous=SOURCE.get();
        Context(String sourceId){SOURCE.set(sourceId);}
        @Override public void close(){if(previous==null)SOURCE.remove();else SOURCE.set(previous);}
    }
    static void log(String operation,Throwable failure) {log(operation,failure,SOURCE.get());}
    static void edit(net.dv8tion.jda.api.interactions.InteractionHook hook,String text,String operation) {
        try {
            hook.editOriginal(text).setAllowedMentions(List.of()).queue(ignored->{},error->delivery(operation,hook,error));
        } catch(RuntimeException error){delivery(operation,hook,error);}
    }
    static void delivery(String operation,net.dv8tion.jda.api.interactions.InteractionHook hook,Throwable failure) {
        var interaction=hook.getInteraction();log(operation,failure,interaction==null?"":interaction.getId());
    }
    static void log(String operation,Throwable failure,String sourceId) {
        var causes=new ArrayList<String>();
        var sites=new ArrayList<String>();
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        Details detail=new Details(Reason.UNSPECIFIED,"");
        int http=0,discord=0;
        for(Throwable current=failure;current!=null&&causes.size()<8&&seen.add(current);current=current.getCause()) {
            causes.add(current.getClass().getSimpleName());
            if(current instanceof Classified classified)detail=classified.details();
            if(current instanceof ChatGptException upstream)http=upstream.status;
            if(current instanceof ErrorResponseException upstream) {
                discord=upstream.getErrorCode();
                if(upstream.getResponse()!=null)http=upstream.getResponse().code;
            }
            for(var frame:current.getStackTrace()) {
                if(frame.getClassName().startsWith("pro.eng.yui.app.viadiscord.")) {
                    sites.add(frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());break;
                }
            }
        }
        System.err.printf("GVD_FAILURE operation=%s source_id=%s reason=%s target=%s declared_bytes=%d actual_bytes=%d expected_bytes=%d causes=%s sites=%s http_status=%d discord_code=%d%n",
                token(operation),snowflake(sourceId),detail.reason(),token(detail.target()),detail.declaredBytes(),detail.actualBytes(),detail.expectedBytes(),
                causes.isEmpty()?"none":String.join(">",causes),sites.isEmpty()?"none":String.join(">",sites),http,discord);
    }
    static void attachmentSizeDifference(String attachmentId,long declared,long actual,long expected) {
        System.err.printf("GVD_ATTACHMENT_SIZE_DIFFERENCE source_id=%s attachment_id=%s declared_bytes=%d actual_bytes=%d expected_bytes=%d%n",
                snowflake(SOURCE.get()),snowflake(attachmentId),declared,actual,expected);
    }
    private static String token(String value) {return value!=null&&value.matches("[a-zA-Z0-9_-]{1,100}")?value:"none";}
    private static String snowflake(String value) {return value!=null&&value.matches("[0-9]{1,20}")?value:"none";}
    private FailureDiagnostics() {}
}
