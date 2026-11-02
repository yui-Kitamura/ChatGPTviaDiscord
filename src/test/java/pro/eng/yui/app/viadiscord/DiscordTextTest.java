package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscordTextTest {
    @Test void boundariesPreserveAllText() {
        for(int length:List.of(1,1901,1999,2000,2001,4000,8000)) {
            String text="あ".repeat(length);
            var chunks=DiscordText.split(text);
            assertEquals(text,String.join("",chunks));
            assertTrue(chunks.stream().allMatch(t->!t.isEmpty() && t.length()<=2000));
        }
    }
    @Test void prefersNewlinesAndPreservesEmojiAtBoundary() {
        assertEquals(List.of("あ".repeat(1200)+"\n","い".repeat(2000)),DiscordText.split("あ".repeat(1200)+"\n"+"い".repeat(2000)));
        String text="あ".repeat(1999)+"😀"+"い".repeat(2000);
        var chunks=DiscordText.split(text);
        assertEquals(text,String.join("",chunks));
        for(String chunk:chunks) {
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length()-1)));
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
        }
    }
    @Test void emptyTextHasNoChunks() {assertTrue(DiscordText.split("").isEmpty());}
}
