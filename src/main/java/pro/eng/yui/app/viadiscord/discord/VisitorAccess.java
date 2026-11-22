package pro.eng.yui.app.viadiscord.discord;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Properties;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import pro.eng.yui.app.viadiscord.data.local.PrivateFiles;
import pro.eng.yui.app.viadiscord.data.local.ResponseSettings;

/**
 * Visitor exceptions apply only to explicitly addressed messages in configured channels.
 */
final public class VisitorAccess {

    private final Path file;
    private final Clock clock;
    private Properties data = new Properties();

    VisitorAccess(Path directory) throws IOException {
        this(directory, Clock.systemUTC());
    }

    VisitorAccess(Path directory, Clock clock) throws IOException {
        file = directory.resolve("ai-visitor.properties");
        this.clock = clock;
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                data.load(reader);
            }
            for (String key : data.stringPropertyNames()) {
                String value = data.getProperty(key);
                if (key.equals("channel")) {
                    validateId(value);
                } else if(key.startsWith("channel.")) {
                    validateId(key.substring("channel.".length()));
                    if("true".equals(value) == false) {
                        throw new IOException("Invalid visitor channel state.");
                    }
                } else if (key.startsWith("last.")) {
                    validateId(key.substring(5));
                    try {
                        if (Long.parseLong(value) < 0) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid visitor rate state.");
                    }
                } else {
                    throw new IOException("Invalid visitor setting.");
                }
            }
            // Keep the old single channel, and persist the new format on the next successful write.
            String legacy = data.getProperty("channel");
            if(legacy != null) {
                data.setProperty("channel."+ legacy, "true");
                data.remove("channel");
            }
        }
    }

    private static void validateId(String value) throws IOException {
        if (value.matches("[0-9]{17,20}") == false) {
            throw new IOException("Invalid visitor ID.");
        }
    }

    public synchronized void setChannel(String channel) throws IOException {
        validateId(channel);
        var next = copy();
        next.setProperty("channel."+ channel, "true");
        save(next);
    }

    synchronized void clearChannel(String channel) throws IOException {
        validateId(channel);
        var next = copy();
        next.remove("channel."+ channel);
        save(next);
    }

    public synchronized boolean isChannel(String channelId) {
        return "true".equals(data.getProperty("channel."+ channelId));
    }

    public synchronized boolean permits(MessageReceivedEvent event, Member member, ResponseSettings settings) {
        if (member == null ||
            member.getUser().isBot() ||
            !event.isFromGuild() ||
            !isChannel(event.getChannel().getId()) ||
            member.getRoles().stream().noneMatch(role -> "GvDvisitor".equals(role.getName()))) {
            return false;
        }

        var message = event.getMessage();
        var known = settings.characters();
        if (message.getMentions().getRoles().stream().anyMatch(
            role -> known.stream().anyMatch(character -> character.roleId().equals(role.getId()))
        )) {
            return true;
        }

        String bot = event.getJDA().getSelfUser().getId();
        String text = message.getContentRaw();
        return message.getMentions().getUsers().stream().anyMatch(user -> bot.equals(user.getId())) &&
            (text.contains("<@" + bot + ">") || text.contains("<@!" + bot + ">"));
    }

    // Persist before invoking the provider. Failed calls also consume a slot; restarts do not reset it.
    public synchronized boolean acquire(String user) throws IOException {
        validateId(user);
        long now = clock.millis();
        String last = data.getProperty("last." + user);
        if (last != null && now - Long.parseLong(last) < 60_000) {
            return false;
        }

        var next = copy();
        for (String key : data.stringPropertyNames()) {
            if (key.startsWith("last.") && now - Long.parseLong(data.getProperty(key)) >= 60_000) {
                next.remove(key);
            }
        }
        next.setProperty("last." + user, Long.toString(now));
        save(next);
        return true;
    }

    private Properties copy() {
        var next = new Properties();
        next.putAll(data);
        return next;
    }

    private void save(Properties next) throws IOException {
        var text = new StringWriter();
        next.store(text, "GvD visitor channels and per-user call timestamps");
        PrivateFiles.write(file, text.toString());
        data = next;
    }
}
