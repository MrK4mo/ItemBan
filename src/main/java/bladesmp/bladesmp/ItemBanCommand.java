package bladesmp.bladesmp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public class ItemBanCommand implements Command<CommandSourceStack> {

    private final ItemBan plugin;

    public ItemBanCommand(ItemBan plugin) {
        this.plugin = plugin;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> createCommand(ItemBan plugin) {
        return Commands.literal("itemban")
                .requires(source -> source.getSender().hasPermission("itemban.admin"))
                .executes(new ItemBanCommand(plugin))
                .then(Commands.literal("reload")
                        .executes(context -> {
                            new ItemBanCommand(plugin).reload(context);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("info")
                        .executes(context -> {
                            new ItemBanCommand(plugin).info(context);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("combat")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    plugin.getServer().getOnlinePlayers().forEach(p ->
                                            builder.suggest(p.getName()));
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    new ItemBanCommand(plugin).combat(context);
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    @Override
    public int run(CommandContext<CommandSourceStack> context) {
        sendHelpMessage(context);
        return Command.SINGLE_SUCCESS;
    }

    private void sendHelpMessage(CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage("§6§l=== ItemBan Hilfe ===");
        context.getSource().getSender().sendMessage("§e/itemban reload §7- Plugin neu laden");
        context.getSource().getSender().sendMessage("§e/itemban info §7- Plugin Informationen anzeigen");
        context.getSource().getSender().sendMessage("§e/itemban combat <player> §7- Combat-Status eines Spielers prüfen");
    }

    private void reload(CommandContext<CommandSourceStack> context) {
        plugin.reloadPlugin();
        context.getSource().getSender().sendMessage("§a§lItemBan Plugin wurde neu geladen!");
    }

    private void info(CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage("§6§l=== ItemBan Info ===");
        context.getSource().getSender().sendMessage("§eCombat-Dauer: §f" + plugin.getCombatDuration() + " Sekunden");
        context.getSource().getSender().sendMessage("§eGebannte Items: §f" + plugin.getBannedItems().size());
        context.getSource().getSender().sendMessage("§eCombat-beschränkte Items: §f" + plugin.getCombatRestrictedItems().size());
        context.getSource().getSender().sendMessage("§eSpieler im Combat: §f" + plugin.getCombatPlayers().size());
    }

    private void combat(CommandContext<CommandSourceStack> context) {
        String playerName = StringArgumentType.getString(context, "player");
        Player target = plugin.getServer().getPlayer(playerName);

        if (target == null) {
            context.getSource().getSender().sendMessage("§c§lSpieler nicht gefunden!");
            return;
        }

        if (plugin.isInCombat(target)) {
            long remaining = plugin.getRemainingCombatTime(target);
            context.getSource().getSender().sendMessage("§e" + target.getName() + " §7ist im Combat (§c" + remaining + "s§7 verbleibend)");
        } else {
            context.getSource().getSender().sendMessage("§e" + target.getName() + " §7ist §anicht §7im Combat");
        }
    }
}