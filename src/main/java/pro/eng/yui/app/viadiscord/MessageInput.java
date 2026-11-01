package pro.eng.yui.app.viadiscord;

import java.util.*;
import net.dv8tion.jda.api.entities.Message;

/** Read the forwarded copy delivered by Discord; never fetch the source channel. */
record MessageInput(String text, List<Message.Attachment> attachments) {
    static MessageInput from(Message message) {
        var text = new StringBuilder(message.getContentRaw());
        var attachments = new ArrayList<>(message.getAttachments());
        for (var snapshot : message.getMessageSnapshots()) {
            var forwarded = new StringBuilder(snapshot.getContentRaw());
            for (var embed : snapshot.getEmbeds()) {
                append(forwarded, embed.getTitle());
                append(forwarded, embed.getDescription());
                for (var field : embed.getFields()) {
                    append(forwarded, field.getName());
                    append(forwarded, field.getValue());
                }
            }
            if (!forwarded.toString().isBlank() || !snapshot.getAttachments().isEmpty()) {
                append(text, "[転送メッセージ・引用資料]\n" + forwarded);
            }
            attachments.addAll(snapshot.getAttachments());
        }
        return new MessageInput(text.toString(), List.copyOf(attachments));
    }

    static MessageInput combine(List<Message> messages) {
        var text = new StringBuilder();
        var attachments = new ArrayList<Message.Attachment>();
        // Present the sender's note before the quoted material in either arrival order.
        messages.stream().sorted(Comparator.comparing(m -> !m.getMessageSnapshots().isEmpty())).forEach(message -> {
            var part = from(message);
            append(text, part.text());
            attachments.addAll(part.attachments());
        });
        return new MessageInput(text.toString(), List.copyOf(attachments));
    }

    private static void append(StringBuilder text, String part) {
        if (part == null || part.isBlank()) return;
        if (!text.isEmpty()) text.append('\n');
        text.append(part);
    }
}
