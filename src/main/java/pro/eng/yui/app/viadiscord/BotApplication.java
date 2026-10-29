package pro.eng.yui.app.viadiscord;

import java.nio.file.Path;
import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

/** Discord message acknowledgements, channel controls, and AI limit guidance. */
public final class BotApplication {
    private BotApplication() {}

    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("Usage: java -jar chatgptviadiscord.jar [--config <properties-file>] [--check-config]");
            System.out.println("Required: DISCORD_BOT_TOKEN, DISCORD_GUILD_ID (or corresponding file properties).");
            return;
        }
        JDA jda = null;
        try {
            Path file = null;
            boolean checkOnly = false;
            for (int index = 0; index < args.length; index++) {
                if ("--config".equals(args[index]) && file == null && index + 1 < args.length) {
                    file = Path.of(args[++index]);
                } else if ("--check-config".equals(args[index]) && !checkOnly) {
                    checkOnly = true;
                } else {
                    throw new IllegalArgumentException("Invalid arguments. Use --help for usage.");
                }
            }
            BotConfig config;
            AiConfig aiConfig;
            CodexUsageConfig usageConfig;
            try {
                config = BotConfig.load(System.getenv(), file);
                aiConfig = AiConfig.load(System.getenv(), file);
                usageConfig = CodexUsageConfig.load(System.getenv(), file);
            } catch (IllegalArgumentException e) {
                // These messages are authored by BotConfig and never include values.
                System.err.println("Configuration error: " + e.getMessage());
                System.exit(1);
                return;
            }
            if (checkOnly) {
                System.out.println("Configuration is valid. No Discord connection was attempted.");
                return;
            }

            ChannelSettings settings;
            try {
                settings = new ChannelSettings(config.stateDirectory().resolve("channel-response.properties"));
            } catch (IOException e) {
                System.err.println("Channel settings could not be loaded; refusing to start with default settings.");
                System.exit(1);
                return;
            }
            var controls = new ChannelControls(config.guildId(), settings);
            var access = new AiAccess(config.stateDirectory(), aiConfig.roleId());
            var transport = new OpenAiTransport(aiConfig.timeout());
            var accounts = aiConfig.enabled() ? new ChatGptAccounts(config.stateDirectory().resolve("chatgpt")) : null;
            var session = aiConfig.enabled() ? new ChatGptSession(accounts, transport, aiConfig.callbackPort()) : null;
            var responseSettings = new ResponseSettings(config.stateDirectory().resolve("response-" + config.guildId() + ".properties"));
            var conversation = aiConfig.enabled() ? new AiConversation(access, controls, session, transport, responseSettings,
                    new ChatHistory(config.stateDirectory().resolve("recent-chat")), conversationInstructions()) : null;
            GvdListener commands = new GvdListener(controls, conversation);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (conversation != null) conversation.close();
                if (session != null) session.close();
                if (accounts != null) try { accounts.close(); } catch (IOException ignored) {}
            }, "gvd-ai-shutdown"));
            var intents = EnumSet.of(GatewayIntent.GUILD_MESSAGES);
            if (aiConfig.enabled()) intents.addAll(EnumSet.of(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS));
            CompletableFuture<JDA> ready = new CompletableFuture<>();
            jda = JDABuilder.createLight(config.token(), intents)
                    .setMemberCachePolicy(aiConfig.enabled() ? net.dv8tion.jda.api.utils.MemberCachePolicy.ALL : net.dv8tion.jda.api.utils.MemberCachePolicy.NONE)
                    .setChunkingFilter(aiConfig.enabled() ? net.dv8tion.jda.api.utils.ChunkingFilter.ALL : net.dv8tion.jda.api.utils.ChunkingFilter.NONE)
                    .setStatus(OnlineStatus.ONLINE)
                    .setAutoReconnect(true)
                    .setEnableShutdownHook(true)
                    .addEventListeners(new LimitCommandListener(config.guildId(), session, transport,
                            usageConfig.enabled() ? new CodexUsageClient(usageConfig) : null))
                    .addEventListeners(new ModeCommandListener(config.guildId(), responseSettings, session))
                    .addEventListeners(new AiCommands(config.guildId(), aiConfig, access, accounts, session, transport))
                    .addEventListeners(commands, new ListenerAdapter() {
                        @Override
                        public void onReady(ReadyEvent event) {
                            ready.complete(event.getJDA());
                        }

                        @Override
                        public void onShutdown(ShutdownEvent event) {
                            ready.completeExceptionally(new IllegalStateException("Gateway shut down during startup."));
                        }
                    })
                    .build();
            ready.get(config.startupTimeout().toSeconds(), TimeUnit.SECONDS);
            if (jda.getGuildById(config.guildId()) == null) {
                throw new IllegalStateException("Bot is not available in the configured guild. Check its invitation and server ID.");
            }
            System.out.printf("BOT_READY bot_id=%s guild_id=%s status=online%n",
                    jda.getSelfUser().getId(), config.guildId());
        } catch (IllegalArgumentException | IllegalStateException e) {
            if (jda != null) jda.shutdownNow();
            System.err.println("Startup failed: check arguments, Bot authentication, target guild ID, and invitation. Use --help for usage.");
            System.exit(1);
        } catch (TimeoutException e) {
            if (jda != null) jda.shutdownNow();
            System.err.println("Startup failed: Discord READY timed out. Check network access and Bot configuration.");
            System.exit(1);
        } catch (InterruptedException e) {
            if (jda != null) jda.shutdownNow();
            Thread.currentThread().interrupt();
            System.err.println("Startup interrupted.");
            System.exit(1);
        } catch (Exception e) {
            if (jda != null) jda.shutdownNow();
            // Do not print third-party exception messages, stack traces, or configuration contents.
            System.err.println("Startup failed (" + e.getClass().getSimpleName()
                    + "). Check the token, configuration file, and network connection.");
            System.exit(1);
        }
    }
    private static String conversationInstructions() throws IOException {
        StringBuilder text=new StringBuilder();
        for (String file : new String[]{"README.md", "AGENTS.md"}) {
            try (var stream=BotApplication.class.getResourceAsStream("/discord-ai/"+file)) {
                if (stream==null) throw new IOException("Conversation instructions are missing.");
                text.append(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).append("\n");
            }
        }
        return text.toString();
    }
}
