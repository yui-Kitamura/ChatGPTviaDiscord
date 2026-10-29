package app.viadiscord;

import java.io.IOException;
import net.dv8tion.jda.api.entities.MessageType;

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
        return acceptsGuild(guildId) && !bot && !webhook
                && (type == MessageType.DEFAULT || type == MessageType.INLINE_REPLY)
                && !settings.isSkipped(guildId, channel, parent);
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
