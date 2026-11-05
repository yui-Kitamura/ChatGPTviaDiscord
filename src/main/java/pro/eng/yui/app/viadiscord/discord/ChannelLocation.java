package pro.eng.yui.app.viadiscord.discord;

import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;

/**
 * Describe the location of a body of chat without fetching additional messages.
 */
public final class ChannelLocation {

    private ChannelLocation() {
    }

    public static String header(Channel channel) {
        return "--- #"+ path(channel) +" (channel_id="+ channel.getId() +") ---\n";
    }

    private static String path(Channel channel) {
        if(channel instanceof ThreadChannel thread) {
            return path(thread.getParentChannel()) +"："+ thread.getName();
        }
        if(channel instanceof ICategorizableChannel categorized && categorized.getParentCategory() != null) {
            return categorized.getParentCategory().getName() +"："+ channel.getName();
        }
        return channel.getName();
    }
}
