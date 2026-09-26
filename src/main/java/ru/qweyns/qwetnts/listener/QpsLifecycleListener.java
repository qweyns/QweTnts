package ru.qweyns.qwetnts.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.jetbrains.annotations.NotNull;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Schedulers;

/**
 * Следит за включением и выключением QweProtectStones.
 *
 * <p><b>Зачем.</b> Bukkit не всегда выдерживает порядок загрузки. В QPS в
 * {@code softdepend} указан QweTnts, а у нашего аддона QPS — в {@code depend}:
 * получается цикл, Bukkit его разрывает, и аддон может включиться первым —
 * когда {@code QpsApi} ещё пуст. Раньше аддон в этой ситуации отключался, и
 * администратор видел «QweProtectStones не найден» на сервере, где QPS
 * прекрасно установлен.</p>
 *
 * <p>Теперь аддон ждёт: этот слушатель поднимается в режиме ожидания и
 * запускает аддон полностью, как только QPS включился.</p>
 *
 * <p>Запуск отложен на следующий тик: регистрировать слушатели, команды и
 * планировщики во время обработки события нельзя — Bukkit в этот момент
 * обходит списки слушателей.</p>
 */
public final class QpsLifecycleListener implements Listener {

    private static final String QPS = "QweProtectStones";

    private final QweTnts plugin;

    public QpsLifecycleListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPluginEnable(@NotNull PluginEnableEvent event) {
        if (!event.getPlugin().getName().equals(QPS)) return;
        if (plugin.isReady()) return;

        Schedulers.runGlobal(plugin, plugin::startup);
    }

    @EventHandler
    public void onPluginDisable(@NotNull PluginDisableEvent event) {
        if (!event.getPlugin().getName().equals(QPS)) return;
        if (!plugin.isReady()) return;

        plugin.markQpsLost();
    }
}
