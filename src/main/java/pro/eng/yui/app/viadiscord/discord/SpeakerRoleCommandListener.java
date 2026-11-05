package pro.eng.yui.app.viadiscord.discord;

import java.io.IOException;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import pro.eng.yui.app.viadiscord.data.local.ResponseSettings;
import pro.eng.yui.app.viadiscord.support.FailureDiagnostics;

public final class SpeakerRoleCommandListener extends ListenerAdapter {

    private final String guildId;
    private final ResponseSettings settings;

    public SpeakerRoleCommandListener(String guildId, ResponseSettings settings) {
        this.guildId = guildId;
        this.settings = settings;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"gvd".equals(event.getName()) || !"role".equals(event.getSubcommandName()) || event.getUser().isBot()) {
            return;
        }
        if (!event.isFromGuild() || !guildId.equals(event.getGuild().getId())) {
            event.reply("このサーバーでは利用できません。").setEphemeral(true).queue();
            return;
        }
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("識別用ロールの登録にはサーバー管理権限が必要です。").setEphemeral(true).queue();
            return;
        }
        var option = event.getOption("role");
        var nameOption = event.getOption("name");
        if (option == null || nameOption == null) {
            event.reply("識別名と識別用ロールを指定してください。").setEphemeral(true).queue();
            return;
        }
        String name = nameOption.getAsString().strip();
        if (name.isEmpty() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
            event.reply("識別名は改行・制御文字を含まない1〜100文字で指定してください。").setEphemeral(true).queue();
            return;
        }
        var role = option.getAsRole();
        if (!guildId.equals(role.getGuild().getId()) || role.isPublicRole()) {
            event.reply("対象サーバーの@everyone以外のロールを指定してください。").setEphemeral(true).queue();
            return;
        }
        var aboutOption = event.getOption("about");
        var about = aboutOption == null ? null : aboutOption.getAsChannel();
        if (about != null && (!guildId.equals(about.getGuild().getId()) || about.getType() != ChannelType.TEXT)) {
            event.reply("aboutには対象サーバーのテキストチャンネルを指定してください。").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(
            hook -> {
                var member = event.getGuild().getMemberById(event.getUser().getId());
                if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
                    FailureDiagnostics.edit(hook, "サーバー管理権限がなくなったため中止しました。", "SPEAKER_ROLE_REPLY");
                    return;
                }
                try {
                    settings.setSpeakerRole(name, role.getId(), about == null ? null : about.getId());
                    FailureDiagnostics.edit(
                        hook,
                        "識別名「" + name + "」にロール " + role.getAsMention() +
                            " を登録しました。同じ識別名のアカウントを共通個体として識別します。" +
                            (about == null ? "" : "\n人物紹介: " + about.getAsMention()),
                        "SPEAKER_ROLE_REPLY"
                    );
                } catch (IOException e) {
                    FailureDiagnostics.log("SPEAKER_ROLE_SAVE", e, event.getId());
                    FailureDiagnostics.edit(hook, "保存できなかったため、識別用ロールの設定は変更していません。", "SPEAKER_ROLE_REPLY");
                }
            },
            error -> FailureDiagnostics.log("SPEAKER_ROLE_ACK", error, event.getId())
        );
    }
}
