package pro.eng.yui.app.viadiscord;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceNotesTest {
    static final String URL="https://example.com/source",OTHER="https://example.com/other";
    @Test void omitsUrlsAlreadyVisibleInPlainTextOrMarkdown() {
        for(String body:List.of(URL,"[資料]("+URL+")","<"+URL+">","参照: "+URL+"。","`"+URL+"`",URL+"."))
            assertEquals("",SourceNotes.format(List.of(URL),body),body);
    }
    @Test void retainsOnlyMissingUrlsInCitationOrder() {
        assertEquals("出典:\n"+OTHER,SourceNotes.format(List.of(URL,OTHER,OTHER),"[資料]("+URL+")"));
        assertEquals("",SourceNotes.format(List.of(),"answer"));
        for(String body:List.of(URL+"/child",URL+"?query=1",URL+"-other",URL+"#section"))
            assertEquals("出典:\n"+URL,SourceNotes.format(List.of(URL),body));
    }
    @Test void threadChecksSummaryAndEveryPostWithoutReadingCharacterMetadata() {
        var reply=new CharacterReply(URL,List.of(new CharacterSpec("guide",OTHER)),List.of(
                new CharacterReply.Post("guide",OTHER,List.of()),new CharacterReply.Post("guide","last",List.of())));
        assertEquals("",SourceNotes.format(List.of(URL,OTHER),SourceNotes.body(reply)));
        var metadataOnly=new CharacterReply("summary",reply.characters(),List.of(new CharacterReply.Post("guide","body",List.of())));
        assertEquals("出典:\n"+OTHER,SourceNotes.format(List.of(OTHER),SourceNotes.body(metadataOnly)));
    }
}
