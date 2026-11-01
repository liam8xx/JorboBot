package bot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Stores all commands, sends them to Discord, and runs the right one when used. */
public class CommandManager extends ListenerAdapter {
    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(Command command) {
        commands.put(command.data().getName(), command);
        System.out.println("Loaded command: /" + command.data().getName());
    }

    public void registerWithDiscord(Guild guild) {
        List<SlashCommandData> data = commands.values().stream()
                .map(Command::data)
                .collect(Collectors.toList());
        guild.updateCommands().addCommands(data).queue(
                ok -> System.out.println("Registered " + data.size() + " command(s)."),
                err -> System.err.println("Failed to register commands: " + err.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        Command command = commands.get(event.getName());
        if (command == null) return;

        try {
            command.execute(event);
        } catch (Exception e) {
            e.printStackTrace();
            String message = "There was an error running that command.";
            if (event.isAcknowledged()) {
                event.getHook().editOriginal(message).queue();
            } else {
                event.reply(message).setEphemeral(true).queue();
            }
        }
    }
}
