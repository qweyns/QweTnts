package ru.qweyns.qwetnts.config;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Все ключи сообщений, которые плагин реально запрашивает.
 *
 * <p>Именно из-за строкового ключа в коде однажды в чат ушёл сырой текст
 * {@code antilag.cooldown}: в lang-файле ключ был написан с опечаткой, и
 * {@code Lang} вернул имя ключа вместо сообщения. Теперь ключи — константы,
 * а {@code LangKeysTest} проверяет, что каждый из них есть во всех
 * lang-файлах: расхождение ловится сборкой, а не игроком.</p>
 */
public final class LangKeys {

    private LangKeys() {
    }

    // --- Общее ---
    public static final String PREFIX = "prefix";
    public static final String NO_PERMISSION = "no_permission";
    public static final String UNKNOWN_SUBCOMMAND = "unknown_subcommand";
    public static final String UNKNOWN_OWNER = "unknown_owner";

    // --- Установка и поджог ---
    public static final String DYNAMITE_PLACED = "dynamite_placed";
    public static final String IGNITION_HINT = "ignition_hint";
    public static final String DYNAMITE_IGNITED = "dynamite_ignited";
    public static final String CANNON_FIRED = "cannon_fired";
    public static final String CANNON_PLACED = "cannon_placed";
    public static final String CANNON_LOADED = "cannon_loaded";
    public static final String CANNON_AMMO_DENIED = "cannon_ammo_denied";
    /** Название ванильного TNT как боеприпаса (без префикса). */
    public static final String CANNON_AMMO_TNT = "cannon_ammo_tnt";
    public static final String DYNAMITE_REMOVED = "dynamite_removed";
    public static final String CANNOT_PLACE_HERE = "cannot_place_here";
    public static final String REGION_DENIED = "region_denied";
    /** Заряд, который по механике HolyWorld не работает в приватах (Б2). */
    public static final String DYNAMITE_ONLY_OUTSIDE = "dynamite_only_outside";

    // --- Запреты ---
    public static final String WORLD_DISABLED = "world_disabled";
    public static final String SPAWN_PROTECTED = "spawn_protected";
    public static final String RAID_BLOCK_DENIED = "raid_block_denied";

    // --- Анти-лаг ---
    public static final String COOLDOWN = "cooldown";
    public static final String PLAYER_LIMIT = "player_limit";
    public static final String CHUNK_LIMIT = "chunk_limit";

    // --- Алерты владельцу привата ---
    /** Сообщение владельцу: его приват атакуют. */
    public static final String ALERT_REGION_ATTACKED = "alert_region_attacked";
    /** То же, но для Discord. */
    public static final String DISCORD_REGION_UNDER_ATTACK = "discord_region_under_attack";

    // --- Бункер (замок HolyWorld) ---
    /** Стена сняла одну стадию. */
    public static final String BUNKER_DEGRADED = "bunker_degraded";
    /** Стена пробита насквозь. */
    public static final String BUNKER_BREACHED = "bunker_breached";
    /** Стена восстановлена (респавн ивента или команда администратора). */
    public static final String BUNKER_RESTORED = "bunker_restored";
    /** То же, но для Discord. */
    public static final String DISCORD_BUNKER_BREACHED = "discord_bunker_breached";
    /** Статус бункера: /qtnt bunker. */
    public static final String COMMAND_BUNKER_STATUS = "command_bunker_status";
    /** Бункер отключён в конфиге. */
    public static final String COMMAND_BUNKER_DISABLED = "command_bunker_disabled";
    /** Стена возвращена в исходную стадию. */
    public static final String COMMAND_BUNKER_RESET = "command_bunker_reset";
    /** Стадия выставлена вручную. */
    public static final String COMMAND_BUNKER_STAGE = "command_bunker_stage";

    // --- Время ---
    public static final String TIME_SECONDS = "time_seconds";
    public static final String TIME_MINUTES_SECONDS = "time_minutes_seconds";

    // --- Команды ---
    /** Заголовок {@code /qtnt info}: название динамита. */
    public static final String COMMAND_INFO_HEADER = "command_info_header";
    /** Строка «ключ: значение» — общая для /qtnt info, stats и clear. */
    public static final String COMMAND_INFO_LINE = "command_info_line";
    /** Заголовок {@code /qtnt stats}. */
    public static final String COMMAND_STATS_HEADER = "command_stats_header";
    /** Подписи строк {@code /qtnt stats}: раньше в чат уходили сырые ключи. */
    public static final String STATS_DYNAMITES_LOADED = "stats_dynamites_loaded";
    public static final String STATS_EXPLOSIONS_TOTAL = "stats_explosions_total";
    /** Отдельная строка на каждый тип взрыва; подстановка {@code %type%}. */
    public static final String STATS_EXPLOSIONS_BY_TYPE = "stats_explosions_by_type";
    public static final String STATS_RAID_BLOCKS_ACTIVE = "stats_raid_blocks_active";
    public static final String STATS_RAID_BLOCKS_MARKED = "stats_raid_blocks_marked";
    public static final String STATS_SPAWNERS_MINED = "stats_spawners_mined";
    public static final String STATS_TEMPORARY_BLOCKS = "stats_temporary_blocks";
    public static final String STATS_REGIONS_DESTROYED = "stats_regions_destroyed";
    /** Версия QweProtectStones, поверх которого работает аддон. */
    public static final String STATS_QPS_VERSION = "stats_qps_version";
    public static final String STATS_QPS_MISSING = "stats_qps_missing";
    /** Подписи строк {@code /qtnt clear}. */
    public static final String CLEARED_RAID_BLOCKS = "cleared_raid_blocks";
    public static final String CLEARED_TEMPORARY_BLOCKS = "cleared_temporary_blocks";
    public static final String CLEARED_PLACED = "cleared_placed";
    public static final String COMMAND_RELOAD = "command_reload";
    public static final String COMMAND_LIST_HEADER = "command_list_header";
    public static final String COMMAND_LIST_EMPTY = "command_list_empty";
    public static final String COMMAND_LIST_ITEM = "command_list_item";
    public static final String COMMAND_LIST_FOOTER = "command_list_footer";
    public static final String COMMAND_HELP = "command_help";
    public static final String USAGE_GIVE = "usage_give";
    public static final String DYNAMITE_NOT_FOUND = "dynamite_not_found";
    public static final String PLAYER_NOT_FOUND = "player_not_found";
    public static final String SPECIFY_PLAYER = "specify_player";
    public static final String INVALID_AMOUNT = "invalid_amount";
    public static final String GIVE_SUCCESS = "give_success";
    public static final String GIVE_SUCCESS_PARTIAL = "give_success_partial";

    // --- Подстановки ---
    public static final String VALUE_AUTO = "value_auto";
    public static final String VALUE_MANUAL = "value_manual";
    /** Стена бункера пробита насквозь. */
    public static final String VALUE_BREACHED = "value_breached";
    /** Стена бункера ещё стоит. */
    public static final String VALUE_INTACT = "value_intact";

    // --- Логи ---
    public static final String LOG_REGION_DESTROYED = "log_region_destroyed";
    public static final String DISCORD_REGION_DESTROYED = "discord_region_destroyed";

    private static final Set<String> ALL = new LinkedHashSet<>(java.util.Arrays.asList(
            PREFIX,
            NO_PERMISSION,
            UNKNOWN_SUBCOMMAND,
            UNKNOWN_OWNER,
            DYNAMITE_PLACED,
            IGNITION_HINT,
            DYNAMITE_IGNITED,
            CANNON_FIRED,
            CANNON_PLACED,
            CANNON_LOADED,
            CANNON_AMMO_DENIED,
            CANNON_AMMO_TNT,
            DYNAMITE_REMOVED,
            CANNOT_PLACE_HERE,
            REGION_DENIED,
            DYNAMITE_ONLY_OUTSIDE,
            WORLD_DISABLED,
            SPAWN_PROTECTED,
            RAID_BLOCK_DENIED,
            COOLDOWN,
            PLAYER_LIMIT,
            CHUNK_LIMIT,
            TIME_SECONDS,
            TIME_MINUTES_SECONDS,
            COMMAND_RELOAD,
            COMMAND_LIST_HEADER,
            COMMAND_LIST_EMPTY,
            COMMAND_LIST_ITEM,
            COMMAND_LIST_FOOTER,
            COMMAND_HELP,
            USAGE_GIVE,
            DYNAMITE_NOT_FOUND,
            PLAYER_NOT_FOUND,
            SPECIFY_PLAYER,
            INVALID_AMOUNT,
            GIVE_SUCCESS,
            GIVE_SUCCESS_PARTIAL,
            VALUE_AUTO,
            VALUE_MANUAL,
            VALUE_BREACHED,
            VALUE_INTACT,
            ALERT_REGION_ATTACKED,
            COMMAND_INFO_HEADER,
            COMMAND_INFO_LINE,
            COMMAND_STATS_HEADER,
            STATS_DYNAMITES_LOADED,
            STATS_EXPLOSIONS_TOTAL,
            STATS_EXPLOSIONS_BY_TYPE,
            STATS_RAID_BLOCKS_ACTIVE,
            STATS_RAID_BLOCKS_MARKED,
            STATS_SPAWNERS_MINED,
            STATS_TEMPORARY_BLOCKS,
            STATS_REGIONS_DESTROYED,
            STATS_QPS_VERSION,
            STATS_QPS_MISSING,
            CLEARED_RAID_BLOCKS,
            CLEARED_TEMPORARY_BLOCKS,
            CLEARED_PLACED,
            BUNKER_DEGRADED,
            BUNKER_BREACHED,
            BUNKER_RESTORED,
            COMMAND_BUNKER_STATUS,
            COMMAND_BUNKER_DISABLED,
            COMMAND_BUNKER_RESET,
            COMMAND_BUNKER_STAGE,
            LOG_REGION_DESTROYED,
            DISCORD_REGION_DESTROYED,
            DISCORD_REGION_UNDER_ATTACK,
            DISCORD_BUNKER_BREACHED));

    /** Все ключи, которые обязан содержать каждый lang-файл. */
    public static @NotNull Set<String> all() {
        return java.util.Collections.unmodifiableSet(ALL);
    }
}
