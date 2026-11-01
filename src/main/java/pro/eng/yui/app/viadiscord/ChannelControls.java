package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

final class ChannelControls {
    private final String guild;
    private final ChannelSettings settings;

    ChannelControls(String guild, ChannelSettings settings) {
        this.guild = guild;
        this.settings = settings;
    }

    boolean acceptsGuild(String candidate) {
        return guild.equals(candidate);
    }

    boolean shouldRespond(String guildId, String channel, String parent, boolean bot, boolean webhook, MessageType type) {
        return eligible(guildId, bot, webhook, type)
                && !settings.isSkipped(guildId, channel, parent);
    }

    boolean shouldRespond(MessageReceivedEvent event) {
        if (!event.isFromGuild()) return false;
        var message = event.getMessage();
        if (!eligible(event.getGuild().getId(), event.getAuthor().isBot(), message.isWebhookMessage(), message.getType())) return false;
        String parent = event.getChannelType().isThread()
                ? event.getChannel().asThreadChannel().getParentChannel().getId() : null;
        if (!settings.isSkipped(event.getGuild().getId(), event.getChannel().getId(), parent)) return true;
        if (message.getMentions().getRoles().stream().anyMatch(role -> role.getName().startsWith("GvD:"))) return true;
        String botId = event.getJDA().getSelfUser().getId();
        String content = message.getContentRaw();
        // Require an explicit user mention, rather than an implicit reply mention.
        return message.getMentions().getUsers().stream().anyMatch(user -> botId.equals(user.getId()))
                && (content.contains("<@" + botId + ">") || content.contains("<@!" + botId + ">"));
    }

    private boolean eligible(String guildId, boolean bot, boolean webhook, MessageType type) {
        return acceptsGuild(guildId) && !bot && !webhook
                && (type == MessageType.DEFAULT || type == MessageType.INLINE_REPLY);
    }

    String update(String guildId, String subcommand, String channelId) throws IOException {
        if (!acceptsGuild(guildId)) throw new IllegalArgumentException("This guild is not configured.");
        boolean skip = switch (subcommand) {
            case "skipch" -> true;
            case "readch" -> false;
            default -> throw new IllegalArgumentException("Unknown gvd subcommand.");
        };
        settings.setSkipped(guildId, channelId, skip);
        return "<#" + channelId + "> " + (skip ? "では👀と返信を停止しました。" : "で👀と返信を再開しました。");
    }
}
