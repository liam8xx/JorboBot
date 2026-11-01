package bot.commands;

import bot.Command;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

/** Example command: copy this file to make new ones. */
public class PingCommand implements Command {
    @Override
    public SlashCommandData data() {
        return Commands.slash("ping", "Check if the bot is alive");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        event.reply("Pong! \uD83C\uDFD3").queue();
    }
}
