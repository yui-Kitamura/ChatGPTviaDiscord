package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/** Shows the shared account's selected GPT model without making inference requests. */
final class ModelPresence extends ListenerAdapter implements AutoCloseable {
    private final String guild;
    private final ChatGptAccounts accounts;
    private final ChatGptSession session;
    private final OpenAiTransport transport;
    private String cachedModel = "", cachedName = "";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("gvd-model-presence").factory());
    private boolean started;

    ModelPresence(String guild, ChatGptAccounts accounts, ChatGptSession session, OpenAiTransport transport) {
        this.guild = guild;
        this.accounts = accounts;
        this.session = session;
        this.transport = transport;
    }

    @Override public synchronized void onReady(ReadyEvent event) {
        if (started || worker.isShutdown()) return;
        started = true;
        // Coalesce setting changes and stay below Discord's five updates per minute limit.
        worker.scheduleWithFixedDelay(() -> {
            try { refresh(event.getJDA()); }
            catch (Exception failure) {
                System.err.println("Model presence could not be updated (" + failure.getClass().getSimpleName() + ").");
            }
        }, 0, 20, TimeUnit.SECONDS);
    }

    void refresh(JDA jda) throws IOException {
        var key = accounts == null ? null : accounts.activeKey(guild);
        var account = key == null ? null : accounts.get(key);
        if (isGpt(account) && account.modelName.isBlank()) {
            if (!account.model.equals(cachedModel)) {
                String selectedModel = account.model;
                var ready = session.ready(guild);
                cachedName = transport.models(ready.accessToken).stream()
                        .filter(model -> model.slug().equals(selectedModel))
                        .map(OpenAiTransport.Model::name).findFirst().orElse("");
                cachedModel = account.model;
            }
            // A catalog request can overlap a model change, account switch, or logout.
            key = accounts.activeKey(guild);
            account = key == null ? null : accounts.get(key);
            if (account != null && account.model.equals(cachedModel) && account.modelName.isBlank())
                account.modelName = cachedName;
        }
        var activity = activity(account);
        if (!Objects.equals(jda.getPresence().getActivity(), activity))
            jda.getPresence().setActivity(activity);
    }

    static Activity activity(ChatGptAccounts.Account account) {
        if (!isGpt(account) || account.modelName.isBlank()) return null;
        String text = account.modelName.strip();
        return Activity.customStatus(text.substring(0, Math.min(text.length(), Activity.MAX_ACTIVITY_NAME_LENGTH)));
    }

    private static boolean isGpt(ChatGptAccounts.Account account) {
        if (account == null || account.accessToken.isEmpty()) return false;
        String model = account.model.strip();
        String family = model.toLowerCase(Locale.ROOT);
        // Bot presence supports name/state/type/url only; custom emoji markup cannot render here.
        return family.equals("gpt") || family.startsWith("gpt-");
    }

    @Override public void onShutdown(ShutdownEvent event) { close(); }
    @Override public void close() { worker.shutdownNow(); }
}
