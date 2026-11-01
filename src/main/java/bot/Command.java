package bot;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

/** Every command implements this. Add a new class in bot/commands and register it in Main. */
public interface Command {
    SlashCommandData data();

    void execute(SlashCommandInteractionEvent event);
}
