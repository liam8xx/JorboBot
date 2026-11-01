package bot.commands;

import bot.Command;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Admin command: posts the rules embed with a Verify button in the current channel. */
public class SetupVerifyCommand implements Command {
    private final String loginUrl;

    public SetupVerifyCommand(String publicUrl) {
        this.loginUrl = publicUrl.replaceAll("/+$", "") + "/login";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("setup-verify", "Post the rules and the Verify button in this channel")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("Only admins can use this.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();

        // The rules text lives in rules.txt on the server, so you can edit it without rebuilding.
        String rules;
        try {
            rules = Files.readString(Path.of("rules.txt")).trim();
        } catch (IOException e) {
            rules = "Welcome! Read the rules, then press **Verify** below to get your VIP role.";
        }
        if (rules.length() > 4000) rules = rules.substring(0, 4000);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Rules & Verification")
                .setDescription(rules)
                .setColor(0x9146FF);

        event.getChannel()
                .sendMessageEmbeds(embed.build())
                .setComponents(ActionRow.of(Button.link(loginUrl, "Verify")))
                .queue(
                        ok -> event.getHook().editOriginal("Posted!").queue(),
                        err -> event.getHook().editOriginal(
                                "I couldn't post here. Give me Send Messages and Embed Links in this channel.").queue());
    }
}
