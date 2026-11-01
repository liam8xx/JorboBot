package bot;

import bot.commands.PingCommand;
import bot.commands.VipCommand;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Collections;

public class Main {
    public static void main(String[] args) throws Exception {
        String token = Config.require("DISCORD_TOKEN");
        String guildId = Config.require("GUILD_ID");

        Twitch twitch = new Twitch();

        CommandManager commands = new CommandManager();
        commands.register(new PingCommand());
        commands.register(new VipCommand(twitch, Config.require("VIP_ROLE_ID"), Config.getBool("GIVE_TWITCH_VIP")));
        // To add a command: create a class in bot/commands and register it here.

        JDA jda = JDABuilder.createLight(token, Collections.emptyList()).build();
        jda.addEventListener(commands);
        jda.awaitReady();
        System.out.println("Logged in as " + jda.getSelfUser().getName());

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            System.err.println("The bot isn't in the server with ID " + guildId + ". Invite it first, then check GUILD_ID.");
            jda.shutdown();
            return;
        }
        commands.registerWithDiscord(guild);
    }
}
