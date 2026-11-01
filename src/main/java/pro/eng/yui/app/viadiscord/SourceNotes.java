package pro.eng.yui.app.viadiscord;

import java.util.List;
import java.util.regex.Pattern;

/** Only append citation URLs that are absent from the displayed answer. */
final class SourceNotes {
    static String format(List<String> sources,String body) {
        var missing=sources.stream().distinct().filter(url->!Pattern.compile(Pattern.quote(url)
                + "(?=$|[\\s<>\"'`)\\]}。、」』]|[.,;!?](?=\\s|$))").matcher(body).find()).toList();
        return missing.isEmpty()?"":"出典:\n"+String.join("\n",missing);
    }
    static String body(CharacterReply reply) {
        return reply.summary()+"\n"+String.join("\n",reply.posts().stream().map(CharacterReply.Post::text).toList());
    }
}
