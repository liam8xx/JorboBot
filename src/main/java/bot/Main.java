package bot;

import bot.commands.PingCommand;
import bot.commands.SetupVerifyCommand;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Collections;

public class Main {
    public static void main(String[] args) throws Exception {
        String token = Config.require("DISCORD_TOKEN");
        String guildId = Config.require("GUILD_ID");
        String publicUrl = Config.require("PUBLIC_URL");

        Twitch twitch = new Twitch();
        LinkStore links = new LinkStore();

        CommandManager commands = new CommandManager();
        commands.register(new PingCommand());
        commands.register(new SetupVerifyCommand(publicUrl));
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

        new WebServer(jda, twitch, links).start();
    }
}
