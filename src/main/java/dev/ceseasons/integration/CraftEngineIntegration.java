package dev.ceseasons.integration;

import dev.ceseasons.agriculture.AgricultureService;
import dev.ceseasons.agriculture.AgricultureSettings;
import dev.ceseasons.calendar.CalendarService;
import dev.ceseasons.season.SeasonSnapshot;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Main plugin only calls register in onLoad, enable/reload/close in its lifecycle. */
public final class CraftEngineIntegration implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Function<UUID, SeasonSnapshot> snapshots;
    private final AgricultureService agriculture;
    private final CalendarService calendar;
    private final java.util.Set<SeasonSensorBehavior> sensors = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean registered;
    private volatile boolean enabled;

    public CraftEngineIntegration(JavaPlugin plugin, Function<UUID, SeasonSnapshot> snapshots) {
        this.plugin = Objects.requireNonNull(plugin);
        this.snapshots = Objects.requireNonNull(snapshots);
        agriculture = new AgricultureService(plugin, snapshots);
        calendar = new CalendarService(plugin, snapshots, agriculture::settings);
    }
    /** No world/snapshot access at registration time. Must precede CE content parsing. */
    public synchronized void register() {
        if (registered) return;
        SeasonCondition.register(snapshots);
        ItemBehaviors.register(Key.of("ce_seasons:calendar"), (pack, path, id, config) -> calendar.behavior());
        BlockBehaviors.register(Key.of("ce_seasons:season_crop"), (block, config) -> new SeasonalGrowthBehavior(block, config, agriculture, false));
        BlockBehaviors.register(Key.of("ce_seasons:season_stem"), (block, config) -> new SeasonalGrowthBehavior(block, config, agriculture, true));
        BlockBehaviors.register(Key.of("ce_seasons:season_sensor"), (block, config) -> {
            SeasonSensorBehavior behavior = new SeasonSensorBehavior(block, config, snapshots, () -> enabled, plugin);
            sensors.add(behavior);
            return behavior;
        });
        registered = true;
    }
    public static void validate(FileConfiguration config) { AgricultureSettings.parse(Objects.requireNonNull(config)); }
    public synchronized void enable(FileConfiguration config) {
        if (!registered) throw new IllegalStateException("register() must run in onLoad before enable()");
        reload(config);
        if (enabled) return;
        enabled = true; agriculture.start(); calendar.start(); sensors.forEach(SeasonSensorBehavior::start);
        plugin.getLogger().warning(sensorSupport().detail());
    }
    public void reload(FileConfiguration config) {
        validate(config); agriculture.reload(config); // never re-register factories/listeners/tasks
    }
    /** Checked against local MC26.3 source and CE26.9.1 injector, not a fake full-support flag. */
    public SensorSupport sensorSupport() {
        return new SensorSupport(true, false, "Season sensor: directional weak getSignal 0..15 supported. MC26.3 ownSignal bridge BLOCKED: CE injector exposes getSignal but not ownSignal; SulfurCube getBestOwnOrNeighbourSignal cannot read this block's own power. Upstream is unchanged.");
    }
    public record SensorSupport(boolean directionalWeakSignal, boolean ownSignal, String detail) {}
    @Override public synchronized void close() {
        enabled = false; sensors.forEach(SeasonSensorBehavior::close); calendar.close(); agriculture.close();
    }
}
