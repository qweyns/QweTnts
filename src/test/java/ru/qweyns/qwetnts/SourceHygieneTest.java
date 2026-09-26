package ru.qweyns.qwetnts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Архитектурные запреты, которые компилятор не проверит.
 *
 * <p>Каждая проверка ниже — это уже исправленный баг, который один раз
 * дошёл до продакшена:</p>
 *
 * <ul>
 *   <li>взрыв учитывался в статистике трижды (три вызова
 *       {@code recordExplosion} на один взрыв);</li>
 *   <li>полный проход по сущностям всех миров ради самовосстановления
 *       счётчиков анти-лага — на Folia это обращение к чужим регионам;</li>
 *   <li>legacy-API Bukkit вместо планировщиков Paper.</li>
 * </ul>
 */
class SourceHygieneTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    @Test
    void explosionIsCountedExactlyOnce() throws IOException {
        assumeSourcesAvailable();

        // Один вызов — в DynamiteExplodeListener (EntityExplodeEvent).
        assertEquals(1, countOccurrences(".recordExplosion("),
                "статистику взрывов должен учитывать ровно один обработчик, "
                        + "иначе каждый взрыв попадает в bStats несколько раз");
    }

    @Test
    void noGlobalEntityScans() throws IOException {
        assumeSourcesAvailable();

        assertEquals(0, countOccurrences("getEntitiesByClass("),
                "полное сканирование сущностей мира небезопасно на Folia "
                        + "и стоит O(всех сущностей) на главном потоке");
    }

    @Test
    void noLegacyBukkitSchedulingOrColors() throws IOException {
        assumeSourcesAvailable();

        List<String> banned = List.of(
                "Bukkit.getScheduler()",
                "getServer().getScheduler()",
                "runTaskLater(",
                "runTaskTimer(",
                "runTaskAsynchronously(",
                "ChatColor."
        );

        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String token : banned) {
                    assertTrue(!source.contains(token),
                            file + ": найдено legacy-API '" + token
                                    + "'. Используйте util/Schedulers и MiniMessage.");
                }
            }
        }
    }

    @Test
    void addonWaitsForQpsInsteadOfDisablingItself() throws IOException {
        assumeSourcesAvailable();

        // Bukkit не всегда выдерживает порядок загрузки: у нас QPS в depend,
        // а в QPS — QweTnts в softdepend. Цикл разрывается, и аддон может
        // включиться первым, когда API приватов ещё пусто. Выключение самого
        // себя в этой ситуации выглядело как «QPS не найден» на сервере, где
        // QPS прекрасно установлен.
        assertTrue(readAllSources().contains("PluginEnableEvent"),
                "аддон должен дожидаться включения QweProtectStones по PluginEnableEvent, "
                        + "а не отключаться при старте");

        assertEquals(0, countOccurrences("disablePlugin(this)"),
                "выключать себя из onEnable нельзя: при порядке загрузки «сначала аддон, "
                        + "потом QPS» это ломает сервер без всякой причины");
    }

    @Test
    void regionRemovalUsesGuaranteedEvent() throws IOException {
        assumeSourcesAvailable();

        assertTrue(readAllSources().contains("RegionDeletedEvent"),
                "журнал уничтоженных приватов должен слушать RegionDeletedEvent: "
                        + "RegionDeleteEvent отменяемый и срабатывает до удаления");

        assertEquals(0, countOccurrences("RegionDeleteEvent event"),
                "обработчик не должен принимать RegionDeleteEvent: любой плагин "
                        + "может отменить удаление, и запись в журнале окажется ложной. "
                        + "RegionDeleteEvent.Reason упоминать можно.");
    }

    @Test
    void schedulerNeverAsksForZeroDelay() throws IOException {
        assumeSourcesAvailable();

        Path schedulers = MAIN.resolve(Path.of("ru", "qweyns", "qwetnts", "util", "Schedulers.java"));
        assumeTrue(Files.isRegularFile(schedulers), "Schedulers.java должен лежать в util/");

        String source = Files.readString(schedulers, StandardCharsets.UTF_8);

        // RegionScheduler#runDelayed с нулевой задержкой бросает
        // IllegalArgumentException: Delay ticks may not be <= 0. Из-за этого
        // каждый поджог падал («Не удалось создать зажжённый динамит»),
        // а тик голограммы повторял ошибку до конца фитиля.
        assertTrue(source.contains("getRegionScheduler().execute("),
                "задача «в регионе локации прямо сейчас» должна уходить в "
                        + "RegionScheduler#execute, а не в runDelayed(…, 0)");

        assertFalse(source.contains("Math.max(0L, delayTicks)"),
                "runDelayed не принимает нулевую задержку: округляйте до тика");

        assertTrue(source.contains("Math.max(1L, delayTicks)"),
                "отложенная задача в регионе должна ждать хотя бы тик");
    }

    /**
     * Настройки из {@code config.yml}, о которых написано в документации,
     * должны реально на что-то влиять.
     *
     * <p>{@code punch-ignites}, {@code chain-radius} и
     * {@code chain-delay-ticks} читались в {@code Settings} и больше нигде
     * не использовались: пункт документации «удар кулаком поджигает заряд»
     * был неправдой, а радиус цепной детонации из конфига не работал.</p>
     */
    @Test
    void documentedSettingsAreWired() throws IOException {
        assumeSourcesAvailable();

        String ignite = sourceOf(Path.of("listener", "DynamiteIgniteListener.java"));
        String explode = sourceOf(Path.of("listener", "DynamiteExplodeListener.java"));

        assumeTrue(!ignite.isEmpty() && !explode.isEmpty(),
                "тест читает исходники слушателей и работает только из корня проекта");

        assertTrue(ignite.contains("dynamites().punchIgnites()"),
                "settings.dynamites.punch-ignites должен разрешать или запрещать "
                        + "поджог ударом, иначе ключ в конфиге ни на что не влияет");
        assertTrue(ignite.contains("chainRadius("),
                "settings.dynamites.chain-radius должен задавать радиус цепной детонации");
        assertTrue(ignite.contains("chainDelayTicks("),
                "settings.dynamites.chain-delay-ticks должен задавать задержку цепного поджога");
        assertTrue(explode.contains("recordRaidBlock("),
                "счётчик рейд-блоков для /qtnt stats должен увеличиваться при отметке");
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    /** Исходник по пути внутри пакета {@code ru/qweyns/qwetnts}. */
    private static String sourceOf(Path relative) throws IOException {
        Path file = MAIN.resolve(Path.of("ru", "qweyns", "qwetnts")).resolve(relative);
        return Files.isRegularFile(file)
                ? Files.readString(file, StandardCharsets.UTF_8)
                : "";
    }

    private static void assumeSourcesAvailable() {
        assumeTrue(Files.isDirectory(MAIN),
                "тест читает исходники проекта и работает только при запуске из корня");
    }

    private static int countOccurrences(String token) throws IOException {
        int total = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                int index = 0;
                while ((index = source.indexOf(token, index)) >= 0) {
                    total++;
                    index += token.length();
                }
            }
        }
        return total;
    }

    private static String readAllSources() throws IOException {
        StringBuilder out = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return out.toString();
    }
}
