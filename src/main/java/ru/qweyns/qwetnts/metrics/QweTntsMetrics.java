package ru.qweyns.qwetnts.metrics;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SingleLineChart;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.stats.ExplosionStats;

import java.util.HashMap;
import java.util.Map;

/** Обвязка над bStats с кастомными чартами. */
public final class QweTntsMetrics {

    private static final int PLUGIN_ID = 34030;

    private final QweTnts plugin;
    private final Metrics metrics;

    public QweTntsMetrics(QweTnts plugin, ExplosionStats stats) {
        this.plugin = plugin;
        this.metrics = new Metrics(plugin, PLUGIN_ID);
        registerCharts(stats);
    }

    private void registerCharts(ExplosionStats stats) {
        metrics.addCustomChart(new SingleLineChart("loaded_dynamite_types",
                () -> plugin.registry().all().size()));

        metrics.addCustomChart(new SingleLineChart("active_raid_blocks",
                () -> plugin.raidBlocks().size()));

        metrics.addCustomChart(new AdvancedPie("explosions_by_type",
                () -> {
                    Map<String, Integer> map = new HashMap<>();
                    for (var e : stats.byType().entrySet()) {
                        map.put(e.getKey(), (int) e.getValue().sumThenReset());
                    }
                    return map;
                }));

        metrics.addCustomChart(new DrilldownPie("dynamite_types_configured",
                () -> {
                    Map<String, Map<String, Integer>> out = new HashMap<>();
                    for (DynamiteType t : plugin.registry().all()) {
                        String parent = t.explosionType();
                        Map<String, Integer> val = new HashMap<>();
                        val.put(t.id(), 1);
                        out.put(parent, val);
                    }
                    return out;
                }));
    }

    public void shutdown() {
        if (metrics != null) metrics.shutdown();
    }
}
