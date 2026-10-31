package pro.eng.yui.app.viadiscord;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/** In-process timer and response counter; no persisted jobs or inference requests. */
final class LimitNotifications extends ListenerAdapter implements AutoCloseable {
    private static final long INTERVAL = Duration.ofHours(1).toNanos();
    private static final long RETRY_DELAY = Duration.ofMinutes(1).toNanos();
    private final LongSupplier clock;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("gvd-limit-notifications").factory());
    private final Supplier<CompletionStage<Void>> publisher;
    private final String guildId;
    private final LimitNotificationConfig config;
    private final Supplier<String> query;
    private long lastSent, retryAfter, responses;
    private boolean started, closed, inFlight, retrying;

    LimitNotifications(String guildId, LimitNotificationConfig config, Supplier<String> query) {
        this.guildId = guildId;
        this.config = config;
        this.query = query;
        this.clock = System::nanoTime;
        this.publisher = null;
    }

    // Manual clock and publisher keep timer/concurrency tests independent of Discord and wall time.
    LimitNotifications(LongSupplier clock, Supplier<CompletionStage<Void>> publisher) {
        this.guildId = null;
        this.config = null;
        this.query = null;
        this.clock = clock;
        this.publisher = publisher;
        lastSent = clock.getAsLong();
    }

    @Override public synchronized void onReady(ReadyEvent event) {
        if (started || closed || !config.enabled()) return;
        started = true;
        lastSent = clock.getAsLong();
        timer.scheduleWithFixedDelay(() -> tick(() -> publish(event.getJDA())), 1, 1, TimeUnit.SECONDS);
    }

    CompletionStage<Void> publish(JDA jda) {
        var guild = jda.getGuildById(guildId);
        var channel = guild == null ? null : guild.getTextChannelById(config.channelId());
        if (channel == null) throw new IllegalStateException("Configured limit channel is unavailable in the target guild.");
        String text;
        try(var diagnostic=FailureDiagnostics.context(config.channelId())){text=query.get();}
        synchronized (this) {
            if (closed) return CompletableFuture.completedFuture(null);
            return channel.sendMessage(text).setAllowedMentions(List.of())
                    .setSuppressedNotifications(true).submit().thenApply(ignored -> null);
        }
    }

    synchronized void responseCompleted() {
        if (!closed) responses++;
    }

    void tick() { tick(publisher); }

    private void tick(Supplier<CompletionStage<Void>> send) {
        final long counted;
        synchronized (this) {
            long now = clock.getAsLong();
            if (closed || inFlight || (retrying && now - retryAfter < 0)
                    || (responses < 10 && now - lastSent < INTERVAL)) return;
            inFlight = true;
            counted = responses;
        }
        try {
            send.get().whenComplete((ignored, error) -> finished(counted, error));
        } catch (Exception error) {
            finished(counted, error);
        }
    }

    private synchronized void finished(long counted, Throwable error) {
        inFlight = false;
        if (closed) return;
        long now = clock.getAsLong();
        if (error == null) {
            responses -= counted; // Preserve responses completed while this notification was being sent.
            lastSent = now;
            retrying = false;
        } else {
            retryAfter = now + RETRY_DELAY;
            retrying = true;
            FailureDiagnostics.log("LIMIT_NOTIFICATION",error,config==null?"":config.channelId());
        }
    }

    @Override public void onShutdown(ShutdownEvent event) { close(); }
    @Override public synchronized void close() {
        closed = true;
        timer.shutdownNow();
    }
}
