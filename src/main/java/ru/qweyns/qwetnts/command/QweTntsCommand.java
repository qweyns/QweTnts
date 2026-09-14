package ru.qweyns.qwetnts.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Команда {@code /qtnt} (алиасы {@code /qwetnts}, {@code /tnts}).
 *
 * <p>Все тексты — из lang-файла; никаких строк в коде.</p>
 */
public final class QweTntsCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_ADMIN = "qwetnts.admin";

    private final QweTnts plugin;

    public QweTntsCommand(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "list" -> list(sender);
            case "give" -> give(sender, args);
            case "help" -> sendHelp(sender);
            default -> plugin.lang().send(sender, "unknown_subcommand", "%command%", label);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Подкоманды
    // ------------------------------------------------------------------

    private void reload(@NotNull CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, "no_permission");
            return;
        }

        plugin.reloadDynamites();
        plugin.lang().send(sender, "command_reload",
                "%count%", String.valueOf(plugin.registry().all().size()));
    }

    private void list(@NotNull CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, "no_permission");
            return;
        }

        var types = plugin.registry().all();
        if (types.isEmpty()) {
            plugin.lang().send(sender, "command_list_empty");
            return;
        }

        plugin.lang().send(sender, "command_list_header",
                "%count%", String.valueOf(types.size()));

        for (DynamiteType type : types) {
            plugin.lang().send(sender, "command_list_item",
                    "%id%", type.id(),
                    "%name%", type.displayName(),
                    "%power%", String.valueOf((int) type.power()),
                    "%explosion%", type.explosionType(),
                    "%siege%", String.valueOf(type.siegeDamage()),
                    "%ignite%", plugin.lang().raw(type.isAutoIgnite(plugin.settings().dynamites().autoIgnite())
                            ? "value_auto" : "value_manual"));
        }
    }

    private void give(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, "no_permission");
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(sender, "usage_give", "%command%", "qtnt");
            return;
        }

        DynamiteType type = plugin.registry().byId(args[1].toLowerCase(Locale.ROOT));
        if (type == null) {
            plugin.lang().send(sender, "dynamite_not_found", "%id%", args[1]);
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null || !target.isOnline()) {
                plugin.lang().send(sender, "player_not_found", "%player%", args[2]);
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            plugin.lang().send(sender, "specify_player");
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                plugin.lang().send(sender, "invalid_amount", "%amount%", args[3]);
                return;
            }
        }
        if (amount <= 0 || amount > 64 * 5) {
            plugin.lang().send(sender, "invalid_amount", "%amount%", String.valueOf(amount));
            return;
        }

        ItemStack stack = type.item();
        if (stack.getType().isAir()) {
            plugin.lang().send(sender, "dynamite_not_found", "%id%", type.id());
            return;
        }

        stack.setAmount(amount);
        var leftover = target.getInventory().addItem(stack);
        int notFit = leftover.values().stream()
                .mapToInt(ItemStack::getAmount)
                .sum();

        if (!leftover.isEmpty()) {
            // Инвентарь забит — остаток бросаем под ноги, чтобы предмет не пропал.
            for (ItemStack rest : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rest);
            }
        }

        if (notFit > 0) {
            plugin.lang().send(sender, "give_success_partial",
                    "%amount%", String.valueOf(amount - notFit),
                    "%name%", type.displayName(),
                    "%player%", target.getName());
            return;
        }

        plugin.lang().send(sender, "give_success",
                "%amount%", String.valueOf(amount),
                "%name%", type.displayName(),
                "%player%", target.getName());
    }

    private void sendHelp(@NotNull CommandSender sender) {
        plugin.lang().sendList(sender, "command_help", "%command%", "qtnt");
    }

    // ------------------------------------------------------------------
    // Tab-completion
    // ------------------------------------------------------------------

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) return Collections.emptyList();

        if (args.length == 1) {
            return filter(List.of("help", "reload", "list", "give"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = new ArrayList<>();
            for (DynamiteType type : plugin.registry().all()) {
                ids.add(type.id());
            }
            return filter(ids, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return filter(names, args[2]);
        }
        return Collections.emptyList();
    }

    private static @NotNull List<String> filter(@NotNull List<String> source, @NotNull String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(value);
        }
        Collections.sort(out);
        return out;
    }
}
