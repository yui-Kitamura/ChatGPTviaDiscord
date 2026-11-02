package pro.eng.yui.app.viadiscord;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.entities.Message;

/** Splits without dropping text or cutting a UTF-16 surrogate pair. */
final class DiscordText {
    private DiscordText() {}

    static List<String> split(String text) {
        var chunks = new ArrayList<String>();
        int start = 0;
        while (text.length() - start > Message.MAX_CONTENT_LENGTH) {
            int end = start + Message.MAX_CONTENT_LENGTH;
            if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
            int newline = text.lastIndexOf('\n', end - 1);
            if (newline >= start + Message.MAX_CONTENT_LENGTH / 2 && !text.substring(start, newline + 1).isBlank() && !text.substring(newline + 1).isBlank()) end = newline + 1;
            chunks.add(text.substring(start, end));
            start = end;
        }
        if (start < text.length()) chunks.add(text.substring(start));
        return List.copyOf(chunks);
    }
}
