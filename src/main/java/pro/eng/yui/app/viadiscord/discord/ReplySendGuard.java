package pro.eng.yui.app.viadiscord.discord;

import java.io.IOException;

@FunctionalInterface
public interface ReplySendGuard {

    boolean canSend(boolean first) throws IOException;
}
