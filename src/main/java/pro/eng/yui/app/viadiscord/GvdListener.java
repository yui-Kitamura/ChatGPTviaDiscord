package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import java.util.List;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

final class GvdListener extends ListenerAdapter {
    private final ChannelControls controls;
    private final java.util.function.Consumer<MessageReceivedEvent> conversation;

    GvdListener(ChannelControls controls) {
        this(controls, null);
    }

    GvdListener(ChannelControls controls, java.util.function.Consumer<MessageReceivedEvent> conversation) {
        this.controls = controls;
        this.conversation = conversation;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || !controls.acceptsGuild(event.getGuild().getId())) return;
        Message message = event.getMessage();
        if (message.isWebhookMessage() || event.getAuthor().isBot()
                || (message.getType() != MessageType.DEFAULT && message.getType() != MessageType.INLINE_REPLY)) return;
        String parent = event.getChannelType().isThread()
                ? event.getChannel().asThreadChannel().getParentChannel().getId() : null;
        if (!controls.shouldRespond(event.getGuild().getId(), event.getChannel().getId(), parent,
                event.getAuthor().isBot(), message.isWebhookMessage(), message.getType())) return;
        if (conversation != null) { conversation.accept(event); return; }
        var feedback=new MessageFeedback(message);
        feedback.pickedUp();
        try {
            quietReply(message).queue(ignored -> feedback.clear(), failure -> {
                logFailure("REPLY", failure);feedback.clear();
            });
        } catch (RuntimeException failure) {
            logFailure("REPLY", failure);feedback.clear();
        }
    }

    static MessageCreateAction quietReply(Message message) {
        return message.reply("botが読みました")
                .mentionRepliedUser(false)
                .setAllowedMentions(List.of())
                .setSuppressedNotifications(true)
                .failOnInvalidReply(true);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"gvd".equals(event.getName()) || event.getUser().isBot()) return;
        if (!"skipch".equals(event.getSubcommandName()) && !"readch".equals(event.getSubcommandName())) return;
        if (!event.isFromGuild() || !controls.acceptsGuild(event.getGuild().getId())) {
            event.reply("このサーバーでは利用できません。").setEphemeral(true).queue();
            return;
        }
        String subcommand = event.getSubcommandName();

        OptionMapping option = event.getOption("ch");
        if (option == null) {
            event.reply("対象チャンネルを指定してください。").setEphemeral(true).queue();
            return;
        }
        var channel = option.getAsChannel();
        if (!controls.acceptsGuild(channel.getGuild().getId()) || channel.getType() == ChannelType.CATEGORY) {
            event.reply("対象サーバーのチャンネルを指定してください。カテゴリは対象外です。").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> {
            try {
                controls.update(event.getGuild().getId(), subcommand, channel.getId());
            } catch (IOException failure) {
                logFailure("SETTINGS_SAVE", failure);
                hook.editOriginal("保存できなかったため、設定は変更していません。")
                        .queue(ignored -> {}, error -> logFailure("COMMAND_REPLY", error));
                return;
            }
            var logChannel = event.getGuild().getSystemChannel();
            if (logChannel == null) {
                hook.editOriginal("設定を変更しましたが、サーバーのデフォルトログチャンネルが未設定のため結果を投稿できませんでした。")
                        .queue(ignored -> {}, failure -> logFailure("COMMAND_REPLY", failure));
                return;
            }
            String response = channel.getAsMention() + ("readch".equals(subcommand) ? " ではROMりません" : " ではROMります");
            try {
                logChannel.sendMessage(response).setAllowedMentions(List.of()).queue(
                        ignored -> hook.editOriginal("デフォルトログチャンネルに結果を投稿しました。")
                                .queue(done -> {}, failure -> logFailure("COMMAND_REPLY", failure)),
                        failure -> reportLogFailure(hook, failure));
            } catch (RuntimeException failure) {
                reportLogFailure(hook, failure);
            }
        }, failure -> logFailure("COMMAND_ACK", failure));
    }

    private static void reportLogFailure(net.dv8tion.jda.api.interactions.InteractionHook hook, Throwable failure) {
        logFailure("COMMAND_LOG", failure);
        hook.editOriginal("設定を変更しましたが、デフォルトログチャンネルへの結果の投稿に失敗しました。")
                .queue(ignored -> {}, error -> logFailure("COMMAND_REPLY", error));
    }

    private static void logFailure(String operation, Throwable failure) {
        // Exception messages can contain external payloads. Never log message text or credentials.
        System.err.printf("GVD_%s_FAILED error_type=%s%n", operation, failure.getClass().getSimpleName());
    }
}
