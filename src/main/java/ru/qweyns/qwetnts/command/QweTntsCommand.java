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
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class QweTntsCommand implements CommandExecutor, TabCompleter {

    private final QweTnts plugin;

    public QweTntsCommand(QweTnts plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("qwetnts.admin")) {
            plugin.lang().send(sender, "error.no-permission");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "list" -> handleList(sender);
            case "give" -> handleGive(sender, args);
            default -> handleHelp(sender);
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadDynamites();
        plugin.lang().send(sender, "command.reload", plugin.registry().all().size());
    }

    private void handleList(CommandSender sender) {
        sender.sendMessage(plugin.lang().get("command.list-header"));
        for (DynamiteType t : plugin.registry().all()) {
            sender.sendMessage(plugin.lang().get("command.list-item",
                    t.id(), t.displayName(), String.format(Locale.ROOT, "%.1f", t.power()), t.explosionType()));
        }
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.lang().send(sender, "error.usage-give");
            return;
        }
        DynamiteType t = plugin.registry().byId(args[1]);
        if (t == null) {
            plugin.lang().send(sender, "error.dynamite-not-found", args[1]);
            return;
        }

        Player target = resolveTarget(sender, args.length >= 3 ? args[2] : null);
        if (target == null) return;

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {
                plugin.lang().send(sender, "error.invalid-amount");
                return;
            }
        }
        amount = Math.max(1, Math.min(64, amount));

        ItemStack stack = t.item();
        stack.setAmount(amount);
        target.getInventory().addItem(stack).values().forEach(drop ->
                target.getWorld().dropItemNaturally(target.getLocation(), drop));

        plugin.lang().send(sender, "command.give-success", amount, t.displayName(), target.getName());
    }

    private Player resolveTarget(CommandSender sender, @Nullable String name) {
        if (name != null) {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) plugin.lang().send(sender, "error.player-not-found", name);
            return p;
        }
        if (!(sender instanceof Player p)) {
            plugin.lang().send(sender, "error.specify-player");
            return null;
        }
        return p;
    }

    private void handleHelp(CommandSender sender) {
        sender.sendMessage(plugin.lang().get("command.help-header"));
        sender.sendMessage(plugin.lang().get("command.help-reload"));
        sender.sendMessage(plugin.lang().get("command.help-list"));
        sender.sendMessage(plugin.lang().get("command.help-give"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command cmd,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length == 1) return filter(List.of("reload", "list", "give"), args[0]);
        if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            return filter(plugin.registry().all().stream()
                    .map(DynamiteType::id).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3 && "give".equalsIgnoreCase(args[0])) return null;
        return List.of();
    }

    private List<String> filter(List<String> src, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : src) if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        return out;
    }
}
