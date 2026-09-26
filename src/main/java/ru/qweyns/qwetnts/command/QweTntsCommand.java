package ru.qweyns.qwetnts.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.config.LangKeys;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.component.ComponentType;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Materials;

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
    /** Идентификатор пушки в {@code /qtnt give}. */
    private static final String CANNON_ID = "cannon";

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
            case "info" -> info(sender, args);
            case "stats" -> stats(sender);
            case "clear" -> clear(sender, args);
            case "bunker" -> bunker(sender, args);
            case "help" -> sendHelp(sender);
            default -> plugin.lang().send(sender, LangKeys.UNKNOWN_SUBCOMMAND, "%command%", label);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Подкоманды
    // ------------------------------------------------------------------

    private void reload(@NotNull CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, LangKeys.NO_PERMISSION);
            return;
        }

        plugin.reloadDynamites();
        plugin.lang().send(sender, LangKeys.COMMAND_RELOAD,
                "%count%", String.valueOf(plugin.registry().all().size()));
    }

    private void list(@NotNull CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, LangKeys.NO_PERMISSION);
            return;
        }

        var types = plugin.registry().all();
        if (types.isEmpty()) {
            plugin.lang().send(sender, LangKeys.COMMAND_LIST_EMPTY);
            return;
        }

        plugin.lang().send(sender, LangKeys.COMMAND_LIST_HEADER,
                "%count%", String.valueOf(types.size()));

        for (DynamiteType type : types) {
            plugin.lang().send(sender, LangKeys.COMMAND_LIST_ITEM,
                    "%id%", type.id(),
                    "%name%", type.displayName(),
                    "%power%", String.valueOf((int) type.explosion().power()),
                    "%explosion%", type.explosion().type(),
                    "%siege%", String.valueOf(type.explosion().siegeDamage()),
                    "%ignite%", plugin.lang().raw(type.isAutoIgnite(plugin.settings().dynamites().autoIgnite())
                            ? LangKeys.VALUE_AUTO : LangKeys.VALUE_MANUAL));
        }

        plugin.lang().send(sender, LangKeys.COMMAND_LIST_FOOTER, "%command%", "qtnt");
    }

    private void give(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, LangKeys.NO_PERMISSION);
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(sender, LangKeys.USAGE_GIVE, "%command%", "qtnt");
            return;
        }

        String raw = args[1].toLowerCase(Locale.ROOT);

        // Тнт-пушка — не динамит, поэтому её выдаём отдельной веткой.
        ItemStack template;
        String name;
        if (CANNON_ID.equals(raw)) {
            if (!plugin.cannon().enabled()) {
                plugin.lang().send(sender, LangKeys.DYNAMITE_NOT_FOUND, "%id%", args[1]);
                return;
            }
            template = plugin.cannonItem().create();
            name = plugin.cannon().item().displayName();
        } else if (raw.startsWith(ComponentType.PREFIX)) {
            // /qtnt give component:explosive — компонент крафта.
            ComponentType component = plugin.components().byId(ComponentType.stripPrefix(raw));
            if (component == null) {
                plugin.lang().send(sender, LangKeys.DYNAMITE_NOT_FOUND, "%id%", args[1]);
                return;
            }
            template = component.create(plugin);
            name = component.item().displayName();
        } else {
            DynamiteType type = plugin.registry().byId(raw);
            if (type == null) {
                plugin.lang().send(sender, LangKeys.DYNAMITE_NOT_FOUND, "%id%", args[1]);
                return;
            }
            template = type.item();
            name = type.displayName();
        }
        if (template.getType().isAir()) {
            plugin.lang().send(sender, LangKeys.DYNAMITE_NOT_FOUND, "%id%", raw);
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null || !target.isOnline()) {
                plugin.lang().send(sender, LangKeys.PLAYER_NOT_FOUND, "%player%", args[2]);
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            plugin.lang().send(sender, LangKeys.SPECIFY_PLAYER);
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                plugin.lang().send(sender, LangKeys.INVALID_AMOUNT, "%amount%", args[3]);
                return;
            }
        }
        if (amount <= 0 || amount > 64 * 5) {
            plugin.lang().send(sender, LangKeys.INVALID_AMOUNT, "%amount%", String.valueOf(amount));
            return;
        }

        ItemStack stack = template.clone();
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
            plugin.lang().send(sender, LangKeys.GIVE_SUCCESS_PARTIAL,
                    "%amount%", String.valueOf(amount - notFit),
                    "%name%", name,
                    "%player%", target.getName());
            return;
        }

        plugin.lang().send(sender, LangKeys.GIVE_SUCCESS,
                "%amount%", String.valueOf(amount),
                "%name%", name,
                "%player%", target.getName());
    }

    /**
     * Параметры динамита: {@code /qtnt info [id]}.
     *
     * <p>Строки — это пути из конфига, поэтому подписи не нужно переводить:
     * администратор видит ровно те ключи, которые потом правит в YAML.</p>
     */
    private void info(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, LangKeys.NO_PERMISSION);
            return;
        }

        if (args.length >= 2) {
            DynamiteType type = plugin.registry().byId(args[1]);
            if (type == null) {
                plugin.lang().send(sender, LangKeys.DYNAMITE_NOT_FOUND, "%id%", args[1]);
                return;
            }
            printType(sender, type);
            return;
        }

        for (DynamiteType type : plugin.registry().all()) {
            printType(sender, type);
        }
    }

    private void printType(@NotNull CommandSender sender, @NotNull DynamiteType type) {
        plugin.lang().send(sender, LangKeys.COMMAND_INFO_HEADER,
                "%name%", type.displayName());

        var explosion = type.explosion();
        row(sender, "id", type.id());
        row(sender, "explosion.type", explosion.type());
        row(sender, "explosion.power", String.valueOf((int) explosion.power()));
        row(sender, "explosion.siege-damage", String.valueOf(explosion.siegeDamage()));
        row(sender, "explosion.works-in-water", String.valueOf(explosion.worksInWater()));
        row(sender, "explosion.max-blocks", String.valueOf(explosion.maxBlocks()));

        var breaking = type.breaking();
        row(sender, "breaking.destructive", String.valueOf(breaking.destructive()));
        row(sender, "breaking.shape", breaking.shape().name());
        if (breaking.shape() == DynamiteType.Breaking.Shape.CUBE) {
            row(sender, "breaking.cube-size", String.valueOf(breaking.cubeSize()));
        }
        row(sender, "breaking.max-resistance", String.valueOf((int) breaking.maxResistance()));
        row(sender, "breaking.scan-radius", String.valueOf(breaking.scanRadius()));
        row(sender, "breaking.blocks", String.valueOf(breaking.blocks().size()));

        row(sender, "regions.only-outside", String.valueOf(type.regions().onlyOutside()));
        row(sender, "raid-block.enabled", String.valueOf(type.raidBlock().enabled()));
        row(sender, "spawner-mining.enabled", String.valueOf(type.spawnerMining().enabled()));
        if (type.spawnerMining().enabled()) {
            row(sender, "spawner-mining.chance", type.spawnerMining().chance() + "%");
        }
        row(sender, "temporary-blocks.enabled", String.valueOf(type.temporary().enabled()));
        row(sender, "ignition.auto", String.valueOf(
                type.isAutoIgnite(plugin.settings().dynamites().autoIgnite())));
        row(sender, "craftable", String.valueOf(type.recipe() != null));
    }

    /** Счётчики с момента запуска сервера: {@code /qtnt stats}. */
    private void stats(@NotNull CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, LangKeys.NO_PERMISSION);
            return;
        }

        plugin.lang().send(sender, LangKeys.COMMAND_STATS_HEADER);

        var stats = plugin.stats();
        row(sender, LangKeys.STATS_DYNAMITES_LOADED, String.valueOf(plugin.registry().all().size()));
        row(sender, LangKeys.STATS_EXPLOSIONS_TOTAL, String.valueOf(totalExplosions()));
        for (var entry : stats.byType().entrySet()) {
            row(sender, LangKeys.STATS_EXPLOSIONS_BY_TYPE,
                    String.valueOf(entry.getValue().sum()),
                    "%type%", entry.getKey());
        }
        row(sender, LangKeys.STATS_RAID_BLOCKS_ACTIVE, String.valueOf(plugin.raidBlocks().size()));
        row(sender, LangKeys.STATS_RAID_BLOCKS_MARKED, String.valueOf(stats.raidBlocksTotal()));
        row(sender, LangKeys.STATS_SPAWNERS_MINED, String.valueOf(stats.spawnersMined()));
        row(sender, LangKeys.STATS_TEMPORARY_BLOCKS, String.valueOf(stats.temporaryBlocks()));
        row(sender, LangKeys.STATS_REGIONS_DESTROYED, String.valueOf(stats.destroyedRegions()));

        // Версия QPS: первое, что просят показать, когда 「взрыв не ломает
        // обсидиан」 — по ней сразу видно, что интеграция поднялась.
        // Голограммы: сколько висит и какие мосты поднялись. Первое, что
        // показывают, когда «таблички нет» — сразу видно, работает ли
        // подсистема вообще и чем именно она рисует.
        row(sender, LangKeys.STATS_HOLOGRAMS,
                String.valueOf(plugin.hologramManager().activeCount()),
                "%providers%", plugin.hologramManager().availableProviders());

        String qpsVersion = plugin.qpsVersion();
        row(sender, qpsVersion == null ? LangKeys.STATS_QPS_MISSING : LangKeys.STATS_QPS_VERSION,
                qpsVersion == null ? "" : qpsVersion);
    }

    private long totalExplosions() {
        long total = 0;
        for (var counter : plugin.stats().byType().values()) {
            total += counter.sum();
        }
        return total;
    }

    /**
     * Аварийная чистка: {@code /qtnt clear <raid-blocks|temporary-blocks|placed|all>}.
     *
     * <p>Раньше единственным способом было удалить файл при остановленном
     * сервере.</p>
     */
    private void clear(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, LangKeys.NO_PERMISSION);
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(sender, LangKeys.UNKNOWN_SUBCOMMAND, "%command%", "clear");
            return;
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        boolean raidBlocks = target.equals("raid-blocks") || target.equals("all");
        boolean temporary = target.equals("temporary-blocks") || target.equals("all");
        boolean placed = target.equals("placed") || target.equals("all");

        if (!raidBlocks && !temporary && !placed) {
            plugin.lang().send(sender, LangKeys.UNKNOWN_SUBCOMMAND, "%command%", "clear");
            return;
        }

        if (raidBlocks) {
            int count = plugin.raidBlocks().size();
            plugin.raidBlocks().clear();
            row(sender, LangKeys.CLEARED_RAID_BLOCKS, String.valueOf(count));
        }
        if (temporary) {
            int count = plugin.temporaryBlocks().size();
            plugin.temporaryBlocks().clear();
            row(sender, LangKeys.CLEARED_TEMPORARY_BLOCKS, String.valueOf(count));
        }
        if (placed) {
            int count = plugin.placedDynamites().size();
            plugin.placedDynamites().clear();
            row(sender, LangKeys.CLEARED_PLACED, String.valueOf(count));
        }
    }

    /**
     * Бункер замка: {@code /qtnt bunker [status|reset|stage <n>]}.
     *
     * <p>{@code status} — стадия стены и сколько осталось до восстановления.
     * {@code reset} — вернуть стену в исходную стадию вручную (то же, что
     * делает таймер респавна). {@code stage} — выставить стадию, чтобы
     * проверить механику, не дожидаясь удачи.</p>
     */
    private void bunker(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            plugin.lang().send(sender, LangKeys.NO_PERMISSION);
            return;
        }

        var service = plugin.bunker();
        if (!service.isEnabled()) {
            plugin.lang().send(sender, LangKeys.COMMAND_BUNKER_DISABLED);
            return;
        }

        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status" -> bunkerStatus(sender);
            case "reset" -> {
                service.restore();
                plugin.lang().send(sender, LangKeys.COMMAND_BUNKER_RESET);
            }
            case "stage" -> bunkerStage(sender, args);
            default -> plugin.lang().send(sender, LangKeys.UNKNOWN_SUBCOMMAND, "%command%", "bunker");
        }
    }

    private void bunkerStatus(@NotNull CommandSender sender) {
        var service = plugin.bunker();
        var settings = service.settings();
        var state = service.state();

        int last = settings.lastStageIndex();
        int index = Math.min(state.stageIndex(), last);
        Material material = settings.wall().stageMaterial(index);

        long until = state.millisUntilRespawn();
        String respawn = until > 0L ? plugin.lang().duration(until) : "\u2014";

        plugin.lang().send(sender, LangKeys.COMMAND_BUNKER_STATUS,
                "%stage%", String.valueOf(index + 1),
                "%of%", String.valueOf(last + 1),
                "%material%", materialName(material),
                "%state%", plugin.lang().raw(state.breached()
                        ? LangKeys.VALUE_BREACHED : LangKeys.VALUE_INTACT),
                "%respawn%", respawn);
    }

    private void bunkerStage(@NotNull CommandSender sender, @NotNull String[] args) {
        var service = plugin.bunker();
        var settings = service.settings();
        int last = settings.lastStageIndex();

        if (args.length < 3) {
            plugin.lang().send(sender, LangKeys.COMMAND_BUNKER_STAGE,
                    "%stage%", String.valueOf(service.state().stageIndex() + 1),
                    "%of%", String.valueOf(last + 1),
                    "%material%", materialName(settings.wall().stageMaterial(service.state().stageIndex())));
            return;
        }

        int stage;
        try {
            stage = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            plugin.lang().send(sender, LangKeys.INVALID_AMOUNT, "%amount%", args[2]);
            return;
        }
        if (stage < 0 || stage > last) {
            plugin.lang().send(sender, LangKeys.INVALID_AMOUNT, "%amount%", args[2]);
            return;
        }

        // Стадию в конфиге меняем и блоки тоже: иначе статус и мир разойдутся.
        service.setStage(stage);
        plugin.lang().send(sender, LangKeys.COMMAND_BUNKER_STAGE,
                "%stage%", String.valueOf(stage + 1),
                "%of%", String.valueOf(last + 1),
                "%material%", materialName(settings.wall().stageMaterial(stage)));
    }

    /** Имя материала для вывода; прочерк, если стадия без материала. */
    private static @NotNull String materialName(@Nullable Material material) {
        return material == null ? "\u2014" : Materials.prettyName(material);
    }

    /**
     * Строка «подпись: значение».
     *
     * <p>Подпись берётся из lang по ключу {@code labelKey}: раньше в чат
     * уходили сырые ключи вроде {@code explosions.total}, и при русском
     * языке половина вывода оставалась английской.</p>
     */
    private void row(@NotNull CommandSender sender,
                     @NotNull String labelKey,
                     @NotNull String value,
                     String... extra) {
        String[] replacements;
        if (extra.length == 0) {
            replacements = new String[] {"%key%", plugin.lang().raw(labelKey), "%value%", value};
        } else {
            replacements = new String[extra.length + 4];
            replacements[0] = "%key%";
            replacements[1] = plugin.lang().raw(labelKey, extra);
            replacements[2] = "%value%";
            replacements[3] = value;
            System.arraycopy(extra, 0, replacements, 4, extra.length);
        }
        plugin.lang().send(sender, LangKeys.COMMAND_INFO_LINE, replacements);
    }

    private void sendHelp(@NotNull CommandSender sender) {
        plugin.lang().sendList(sender, LangKeys.COMMAND_HELP, "%command%", "qtnt");
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
            return filter(List.of("help", "reload", "list", "give", "info", "stats", "clear", "bunker"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("give"))) {
            List<String> ids = new ArrayList<>();
            for (DynamiteType type : plugin.registry().all()) {
                ids.add(type.id());
            }
            if (args[0].equalsIgnoreCase("give")) {
                if (plugin.cannon().enabled()) {
                    ids.add(CANNON_ID);
                }
                for (ComponentType component : plugin.components().all()) {
                    ids.add(ComponentType.PREFIX + component.id());
                }
            }
            return filter(ids, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            return filter(List.of("raid-blocks", "temporary-blocks", "placed", "all"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bunker")) {
            return filter(List.of("status", "reset", "stage"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bunker") && args[1].equalsIgnoreCase("stage")) {
            int last = plugin.bunker().settings().lastStageIndex();
            List<String> stages = new ArrayList<>();
            for (int i = 0; i <= last; i++) {
                stages.add(String.valueOf(i));
            }
            return filter(stages, args[2]);
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
