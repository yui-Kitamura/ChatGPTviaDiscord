package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded recent successful exchanges; raw user/assistant turns remain distinct. */
final class ChatHistory {
    public static final class History {
        public List<Map<String,Object>> turns=new ArrayList<>();
        public List<String> ids=new ArrayList<>();
        public long nextPost=1;
        public Map<Long,String> postMessages=new LinkedHashMap<>();
        public History(){}
    }
    private final Path directory;
    ChatHistory(Path directory){this.directory=directory;}
    History load(String key) throws IOException {
        var file=path(key);var value=Files.exists(file)?ChatGptAccounts.JSON.readValue(Files.readString(file),History.class):new History();
        if(value.postMessages==null)value.postMessages=new LinkedHashMap<>();
        if(value.turns==null||value.ids==null||value.turns.size()>40||value.nextPost<1||value.nextPost>9_000_000_000_000_000L)throw new IOException("Invalid recent history.");
        for(var turn:value.turns)if(!Set.of("user","assistant").contains(turn.get("role"))
                ||!(turn.get("content") instanceof String||turn.get("content") instanceof List<?>))throw new IOException("Invalid history item.");
        return value;
    }
    void save(String key,History history) throws IOException {
        trim(history.turns);
        while(history.ids.size()>200)history.ids.remove(0);
        PrivateFiles.write(path(key),ChatGptAccounts.JSON.writeValueAsString(history));
    }
    /** Bound inline attachment history as well as text, before sending and saving. */
    static void trim(List<Map<String,Object>> turns) throws IOException {
        while(turns.size()>40)removeOldestExchange(turns);
        while(turns.size()>2&&(turns.stream().mapToInt(t->textLength(t.get("content"))).sum()>64000
                ||ChatGptAccounts.JSON.writeValueAsBytes(turns).length>16*1024*1024)) {
            removeOldestExchange(turns);
        }
    }
    private static void removeOldestExchange(List<Map<String,Object>> turns) {
        turns.remove(0);
        if(!turns.isEmpty()&&"assistant".equals(turns.getFirst().get("role")))turns.remove(0);
    }
    private static int textLength(Object content) {
        if(content instanceof String text)return text.length();
        if(content instanceof List<?> parts)return parts.stream().filter(p->p instanceof Map<?,?>)
                .mapToInt(p->{Object text=((Map<?,?>)p).get("text");return text instanceof String s?s.length():0;}).sum();
        return 0;
    }
    private Path path(String key){if(!key.matches("[0-9a-f-]{1,160}"))throw new IllegalArgumentException("Invalid conversation key.");return directory.resolve(key+".json");}
}
