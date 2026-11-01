package bot.commands;

import bot.Command;
import bot.Twitch;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VipCommand implements Command {
    // Twitch calls take a moment, so they run on their own threads.
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    private final Twitch twitch;
    private final String roleId;
    private final boolean giveTwitchVip;

    public VipCommand(Twitch twitch, String roleId, boolean giveTwitchVip) {
        this.twitch = twitch;
        this.roleId = roleId;
        this.giveTwitchVip = giveTwitchVip;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("vip", "Get the VIP role in Discord")
                .addOption(OptionType.STRING, "twitch_name", "Your Twitch username", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) {
            event.reply("Use this command in the server.").setEphemeral(true).queue();
            return;
        }

        String name = event.getOption("twitch_name").getAsString();
        event.deferReply(true).queue(); // only the user sees the reply

        POOL.submit(() -> {
            try {
                String userId = twitch.getUserId(name);
                if (userId == null) {
                    reply(event, "I couldn't find a Twitch user called **" + name + "**.");
                    return;
                }

                Role role = guild.getRoleById(roleId);
                if (role == null) {
                    reply(event, "The VIP role wasn't found. Please tell a mod to check VIP_ROLE_ID.");
                    return;
                }

                if (giveTwitchVip) {
                    Twitch.Result result = twitch.addVip(userId);
                    if (!result.ok()) {
                        reply(event, result.reason());
                        return;
                    }
                } else if (!twitch.isVip(userId)) {
                    reply(event, "**" + name + "** isn't a VIP on Twitch, so I can't give you the role.");
                    return;
                }

                String success = giveTwitchVip
                        ? "You now have VIP on Twitch and in Discord! \uD83C\uDF89"
                        : "Verified! You now have the VIP role. \uD83C\uDF89";

                guild.addRoleToMember(member, role).queue(
                        ok -> reply(event, success),
                        err -> reply(event, "I couldn't give you the role. A mod needs to move my role above the VIP role."));
            } catch (Exception e) {
                e.printStackTrace();
                reply(event, "Something went wrong. Please tell a mod.");
            }
        });
    }

    private static void reply(SlashCommandInteractionEvent event, String message) {
        event.getHook().editOriginal(message).queue();
    }
}
