package pro.eng.yui.app.viadiscord.discord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion;
import org.junit.jupiter.api.Test;

class ChannelLocationTest {

    @Test
    void channelWithoutCategoryKeepsItsNameAndId() {
        var channel = mock(TextChannel.class);
        when(channel.getName()).thenReturn("雑談");
        when(channel.getId()).thenReturn("channel");
        assertEquals("--- #雑談 (channel_id=channel) ---\n", ChannelLocation.header(channel));
    }

    @Test
    void channelIncludesCategory() {
        var channel = mock(TextChannel.class);
        var category = mock(Category.class);
        when(category.getName()).thenReturn("交流");
        when(channel.getParentCategory()).thenReturn(category);
        when(channel.getName()).thenReturn("雑談");
        when(channel.getId()).thenReturn("channel");
        assertEquals("--- #交流：雑談 (channel_id=channel) ---\n", ChannelLocation.header(channel));
    }

    @Test
    void threadIncludesParentAndItsCategoryAndKeepsThreadId() {
        var thread = mock(ThreadChannel.class);
        var parent = mock(IThreadContainerUnion.class, withSettings().extraInterfaces(ICategorizableChannel.class));
        var category = mock(Category.class);
        when(category.getName()).thenReturn("開発");
        when(((ICategorizableChannel) parent).getParentCategory()).thenReturn(category);
        when(parent.getName()).thenReturn("相談");
        when(thread.getParentChannel()).thenReturn(parent);
        when(thread.getName()).thenReturn("表示の改善");
        when(thread.getId()).thenReturn("thread");
        assertEquals("--- #開発：相談：表示の改善 (channel_id=thread) ---\n", ChannelLocation.header(thread));
        when(((ICategorizableChannel) parent).getParentCategory()).thenReturn(null);
        assertEquals("--- #相談：表示の改善 (channel_id=thread) ---\n", ChannelLocation.header(thread));
    }
}
