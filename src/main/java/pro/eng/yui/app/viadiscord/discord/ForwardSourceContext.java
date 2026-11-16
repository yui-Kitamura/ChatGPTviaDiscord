package pro.eng.yui.app.viadiscord.discord;

import java.util.*;
import net.dv8tion.jda.api.entities.*;

/**
 * Bounded source excerpts, kept separate from the immutable forwarded snapshot.
 */
public final class ForwardSourceContext {

    public static String load(Guild guild, Member requester, List<Message> messages) {
        var result = new StringBuilder();
        var seen = new HashSet<String>();
        for (var message : messages) {
            if (message.getMessageSnapshots().isEmpty()) {
                continue;
            }

            var ref = message.getMessageReference();
            if (ref == null) {
                continue;
            }
            String sourceGuild = ref.getGuildId();
            // Discord may omit guild_id on forwards. The neighborhood loader still
            // resolves the source channel exclusively through the current guild.
            if (sourceGuild != null && !"0".equals(sourceGuild) && !guild.getId().equals(sourceGuild)) {
                continue;
            }

            String source = ref.getChannelId() + "/" + ref.getMessageId();
            if (!seen.add(source)) {
                continue;
            }
            // A batch currently has at most one forward. Keep a bound if that changes.

            if (result.length() > 0) {
                break;
            }
            result.append(MessageNeighborhoodContext.load(
                guild, requester, ref.getChannelId(), ref.getMessageId(), "転送元", "元投稿"
            ));
        }
        return result.toString();
    }

}
