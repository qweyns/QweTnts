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
    public static final String PLAYERS_ONLY = "players_only";
    public static final String UNKNOWN_OWNER = "unknown_owner";

    // --- Установка и поджог ---
    public static final String DYNAMITE_PLACED = "dynamite_placed";
    public static final String IGNITION_HINT = "ignition_hint";
    public static final String DYNAMITE_IGNITED = "dynamite_ignited";
    public static final String DYNAMITE_REMOVED = "dynamite_removed";
    public static final String CANNOT_PLACE_HERE = "cannot_place_here";
    public static final String REGION_DENIED = "region_denied";

    // --- Запреты ---
    public static final String WORLD_DISABLED = "world_disabled";
    public static final String SPAWN_PROTECTED = "spawn_protected";
    public static final String RAID_BLOCK_DENIED = "raid_block_denied";

    // --- Анти-лаг ---
    public static final String COOLDOWN = "cooldown";
    public static final String PLAYER_LIMIT = "player_limit";
    public static final String CHUNK_LIMIT = "chunk_limit";

    // --- Время ---
    public static final String TIME_SECONDS = "time_seconds";
    public static final String TIME_MINUTES_SECONDS = "time_minutes_seconds";

    // --- Команды ---
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

    // --- Логи ---
    public static final String LOG_REGION_DESTROYED = "log_region_destroyed";

    private static final Set<String> ALL = new LinkedHashSet<>(java.util.Arrays.asList(
            PREFIX,
            NO_PERMISSION,
            UNKNOWN_SUBCOMMAND,
            PLAYERS_ONLY,
            UNKNOWN_OWNER,
            DYNAMITE_PLACED,
            IGNITION_HINT,
            DYNAMITE_IGNITED,
            DYNAMITE_REMOVED,
            CANNOT_PLACE_HERE,
            REGION_DENIED,
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
            LOG_REGION_DESTROYED));

    /** Все ключи, которые обязан содержать каждый lang-файл. */
    public static @NotNull Set<String> all() {
        return java.util.Collections.unmodifiableSet(ALL);
    }
}
