package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LimitNotificationsTest {
    static final long HOUR = Duration.ofHours(1).toNanos();
    static final long MINUTE = Duration.ofMinutes(1).toNanos();

    @Test void hourlyNotificationResetsBothThresholds() {
        var clock = new AtomicLong();
        var sent = new AtomicInteger();
        try (var notifications = new LimitNotifications(clock::get, () -> {
            sent.incrementAndGet(); return CompletableFuture.completedFuture(null);
        })) {
            for (int i=0;i<9;i++) notifications.responseCompleted();
            clock.set(HOUR-1); notifications.tick(); assertEquals(0,sent.get());
            clock.set(HOUR); notifications.tick(); assertEquals(1,sent.get());
            notifications.responseCompleted(); notifications.tick(); assertEquals(1,sent.get());
            clock.set(2*HOUR-1); notifications.tick(); assertEquals(1,sent.get());
            clock.set(2*HOUR); notifications.tick(); assertEquals(2,sent.get());
        }
    }

    @Test void tenRepliesResetTheHourlyDeadlineAndOverlappingTriggersSendOnce() {
        var clock = new AtomicLong(); var sent = new AtomicInteger();
        try (var notifications = new LimitNotifications(clock::get, () -> {
            sent.incrementAndGet(); return CompletableFuture.completedFuture(null);
        })) {
            clock.set(HOUR/2);
            for(int i=0;i<10;i++)notifications.responseCompleted();
            notifications.tick(); notifications.tick(); assertEquals(1,sent.get());
            clock.set(HOUR); notifications.tick(); assertEquals(1,sent.get());
            for(int i=0;i<10;i++)notifications.responseCompleted();
            clock.set(HOUR+HOUR/2); notifications.tick(); notifications.tick(); assertEquals(2,sent.get());
        }
    }

    @Test void inFlightNotificationPreservesNewResponsesAndPreventsDuplicateSends() throws Exception {
        var clock = new AtomicLong(HOUR); var sent = new AtomicInteger();
        var pending = new CompletableFuture<Void>();
        try (var notifications = new LimitNotifications(clock::get, () -> {
            sent.incrementAndGet(); return pending;
        })) {
            for(int i=0;i<10;i++)notifications.responseCompleted();
            try(var pool=Executors.newFixedThreadPool(4)) {
                var tasks=new java.util.ArrayList<Future<?>>();
                for(int i=0;i<20;i++)tasks.add(pool.submit(notifications::tick));
                for(var task:tasks)task.get();
            }
            assertEquals(1,sent.get());
            for(int i=0;i<10;i++)notifications.responseCompleted();
            notifications.tick(); assertEquals(1,sent.get());
            pending.complete(null); notifications.tick(); assertEquals(2,sent.get());
            notifications.tick(); assertEquals(2,sent.get());
        }
    }

    @Test void failedDeliveryBacksOffAndRetainsTheThreshold() {
        var clock = new AtomicLong(); var attempts = new AtomicInteger();
        try (var notifications = new LimitNotifications(clock::get, () -> {
            if(attempts.incrementAndGet()==1)return CompletableFuture.failedFuture(new IllegalStateException("secret"));
            return CompletableFuture.completedFuture(null);
        })) {
            clock.set(HOUR); notifications.tick(); assertEquals(1,attempts.get());
            for(int i=0;i<20;i++)notifications.responseCompleted();
            notifications.tick(); clock.addAndGet(MINUTE-1); notifications.tick(); assertEquals(1,attempts.get());
            clock.incrementAndGet(); notifications.tick(); assertEquals(2,attempts.get());
            notifications.tick(); assertEquals(2,attempts.get());
        }
    }

    @Test void synchronousFailureDoesNotStopFutureChecksAndClosePreventsSending() {
        var clock = new AtomicLong(); var attempts = new AtomicInteger();
        var notifications = new LimitNotifications(clock::get, () -> {
            attempts.incrementAndGet(); throw new IllegalStateException("secret");
        });
        clock.set(HOUR); notifications.tick();
        clock.addAndGet(MINUTE); notifications.tick(); assertEquals(2,attempts.get());
        notifications.close(); clock.addAndGet(HOUR); notifications.tick(); assertEquals(2,attempts.get());
    }

    @Test void publishesSharedQueryOnlyToConfiguredGuildChannelWithoutMentions() throws Exception {
        var jda=mock(net.dv8tion.jda.api.JDA.class);
        var guild=mock(net.dv8tion.jda.api.entities.Guild.class);
        var channel=mock(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class);
        var action=mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class,RETURNS_SELF);
        when(jda.getGuildById("123456789012345678")).thenReturn(guild);
        when(guild.getTextChannelById("234567890123456789")).thenReturn(channel);
        when(channel.sendMessage("shared limit result")).thenReturn(action);
        when(action.submit()).thenReturn(CompletableFuture.completedFuture(null));
        var queries=new AtomicInteger();
        try(var notifications=new LimitNotifications("123456789012345678",new LimitNotificationConfig("234567890123456789"),()->{
            queries.incrementAndGet();return "shared limit result";
        })) {
            notifications.publish(jda).toCompletableFuture().get();
            assertEquals(1,queries.get());
            verify(action).setAllowedMentions(argThat(java.util.Collection::isEmpty));
            verify(action).setSuppressedNotifications(true);
            when(guild.getTextChannelById("234567890123456789")).thenReturn(null);
            assertThrows(IllegalStateException.class,()->notifications.publish(jda));
            assertEquals(1,queries.get());
        }
    }

    @Test void disabledDestinationNeverStartsOrQueries() {
        var queries=new AtomicInteger();
        try(var notifications=new LimitNotifications("123456789012345678",new LimitNotificationConfig(""),()->{
            queries.incrementAndGet();return "unused";
        })) {
            notifications.onReady(null);
            assertEquals(0,queries.get());
        }
    }
}
