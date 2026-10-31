package pro.eng.yui.app.viadiscord;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

/** Discord supplies no shared ID for a forward and its optional note. Match only adjacent pairs. */
final class ForwardMessageBatcher implements AutoCloseable {
    static final long WINDOW_MILLIS = 1500;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Consumer<List<MessageReceivedEvent>> dispatch;
    private final long window;
    private boolean closed;
    private record Pending(MessageReceivedEvent event, long receivedAt, ScheduledFuture<?> timeout) {}

    ForwardMessageBatcher(Consumer<List<MessageReceivedEvent>> dispatch) { this(dispatch, WINDOW_MILLIS); }
    ForwardMessageBatcher(Consumer<List<MessageReceivedEvent>> dispatch, long window) {
        this.dispatch = dispatch; this.window = window;
    }
    synchronized void accept(MessageReceivedEvent event) {
        if (closed) return;
        String key = event.getGuild().getId() + ":" + event.getChannel().getId();
        Pending previous = pending.remove(key);
        if (previous != null) {
            previous.timeout().cancel(false);
            if (previous.event().getMessage().getId().equals(event.getMessage().getId())) {
                schedule(key, previous.event(), previous.receivedAt()); return;
            }
            if (System.nanoTime() - previous.receivedAt() <= TimeUnit.MILLISECONDS.toNanos(window)
                    && matches(previous.event(), event, window)) {
                dispatch.accept(List.of(previous.event(), event)); return;
            }
            dispatch.accept(List.of(previous.event()));
        }
        if (pending.size() >= 128) flush(pending.keySet().iterator().next());
        schedule(key, event, System.nanoTime());
    }
    private void schedule(String key, MessageReceivedEvent event, long receivedAt) {
        long remaining = Math.max(0, window - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - receivedAt));
        pending.put(key, new Pending(event, receivedAt, timer.schedule(() -> flush(key), remaining, TimeUnit.MILLISECONDS)));
    }
    private synchronized void flush(String key) {
        Pending item = pending.remove(key);
        if (item != null) { item.timeout().cancel(false); dispatch.accept(List.of(item.event())); }
    }
    synchronized void flush() {
        for (String key : List.copyOf(pending.keySet())) flush(key);
    }
    static boolean matches(MessageReceivedEvent a, MessageReceivedEvent b, long window) {
        if (!a.getGuild().getId().equals(b.getGuild().getId())
                || !a.getChannel().getId().equals(b.getChannel().getId())
                || !a.getAuthor().getId().equals(b.getAuthor().getId())) return false;
        var first = a.getMessage(); var second = b.getMessage();
        boolean forwardA = !first.getMessageSnapshots().isEmpty(), forwardB = !second.getMessageSnapshots().isEmpty();
        if (forwardA == forwardB) return false;
        var note = forwardA ? second : first;
        // Explicit replies and attachment posts are independent requests, not optional notes.
        if (note.getMessageReference() != null || note.getContentRaw().isBlank() || !note.getAttachments().isEmpty()) return false;
        var timeA = first.getTimeCreated(); var timeB = second.getTimeCreated();
        return timeA != null && timeB != null && Math.abs(java.time.Duration.between(timeA, timeB).toMillis()) <= window;
    }
    @Override public synchronized void close() {
        closed = true; pending.values().forEach(p -> p.timeout().cancel(false)); pending.clear(); timer.shutdownNow();
    }
}
