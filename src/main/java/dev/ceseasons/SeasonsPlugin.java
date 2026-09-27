package dev.ceseasons;

import dev.ceseasons.climate.ClimateService;
import dev.ceseasons.command.SeasonsCommand;
import dev.ceseasons.config.SeasonsConfig;
import dev.ceseasons.integration.CraftEngineIntegration;
import dev.ceseasons.season.SeasonService;
import dev.ceseasons.season.SeasonSnapshot;
import dev.ceseasons.storage.SeasonStore;
import dev.ceseasons.visual.SeasonVisualService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class SeasonsPlugin extends JavaPlugin implements Listener {
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean reloadPending = new AtomicBoolean();
    private volatile SeasonService seasons;
    private volatile SeasonsConfig settings;
    private CraftEngineIntegration integration;
    private ClimateService climate;
    private SeasonVisualService visuals;
    private SeasonStore store;
    private ScheduledTask clockTask;
    private World clockSource;
    private long ticksSinceSave;
    private volatile long lifecycleEpoch;

    @Override
    public void onLoad() {
        integration = new CraftEngineIntegration(this, this::snapshot);
        integration.register();
    }

    @Override
    public void onEnable() {
        try {
            dev.ceseasons.platform.MinecraftVersion version =
                    dev.ceseasons.platform.MinecraftVersion.fromId(Bukkit.getMinecraftVersion());
            saveDefaultConfig();
            settings = SeasonsConfig.parse(getConfig());
            store = new SeasonStore(getDataFolder().toPath().resolve("seasons.properties"),
                    failure -> getLogger().log(Level.SEVERE, "Season persistence failed", failure));
            seasons = new SeasonService(settings.dayTicks(), settings.daysPerSubSeason(),
                    settings.startingSubSeason(), settings.progressWhileEmpty(), store.load());
            integration.enable(getConfig());
            visuals = new SeasonVisualService(this, this::snapshot);
            visuals.start(getConfig());
            climate = new ClimateService(this, this::snapshot);
            climate.start(getConfig());
            Bukkit.getPluginManager().registerEvents(this, this);
            registerCommand("ceseasons", "CraftEngine seasonal calendar and administration",
                    new SeasonsCommand(this));
            clockTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                if (stopping.get()) return;
                try {
                    if (clockSource == null || Bukkit.getWorld(clockSource.getUID()) != clockSource) {
                        if (clockSource != null) resetClockSource();
                        reconcileWorlds();
                    }
                    if (clockSource == null) return;
                    seasons.tick(clockSource.getFullTime(), !Bukkit.getOnlinePlayers().isEmpty());
                    if (++ticksSinceSave >= settings.saveIntervalTicks()) {
                        saveState();
                    }
                } catch (RuntimeException failure) {
                    task.cancel();
                    getLogger().log(Level.SEVERE, "Season clock stopped after an error", failure);
                    Bukkit.getPluginManager().disablePlugin(this);
                }
            }, 1L, 1L);
            getLogger().info("CraftEngineSeasons enabled for Minecraft " + version.id()
                    + ". Client and Folia integration validation is required.");
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Cannot enable CraftEngineSeasons", failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    public SeasonSnapshot snapshot(UUID worldId) {
        SeasonService current = seasons;
        return current == null ? null : current.snapshot(worldId);
    }

    public SeasonService seasons() {
        return seasons;
    }

    public boolean isStopping() {
        return stopping.get();
    }

    public void runGlobal(Runnable action) {
        long epoch = lifecycleEpoch;
        if (stopping.get() || !isEnabled()) return;
        Bukkit.getGlobalRegionScheduler().execute(this, () -> {
            if (!stopping.get() && lifecycleEpoch == epoch) action.run();
        });
    }

    public void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            if (!stopping.get()) {
                player.getScheduler().run(this, task -> player.sendMessage(message), null);
            }
        } else {
            sender.sendMessage(message);
        }
    }

    public void saveState() {
        if (!Bukkit.isGlobalTickThread()) {
            throw new IllegalStateException("Season changes must be saved from the global region");
        }
        ticksSinceSave = 0;
        store.save(seasons.storedSeasons());
    }

    public void reloadSettings(CommandSender sender) {
        if (!reloadPending.compareAndSet(false, true)) {
            reply(sender, "已有配置重载正在进行。");
            return;
        }
        long epoch = lifecycleEpoch;
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                YamlConfiguration candidate = new YamlConfiguration();
                candidate.load(new File(getDataFolder(), "config.yml"));
                SeasonsConfig checked = SeasonsConfig.parse(candidate);
                CraftEngineIntegration.validate(candidate);
                ClimateService.validate(candidate);
                SeasonVisualService.validate(candidate);
                runGlobal(() -> {
                    try {
                        if (epoch != lifecycleEpoch) return;
                        if (!checked.sourceWorld().equals(settings.sourceWorld())) {
                            throw new IllegalArgumentException("Changing clock.source-world requires a restart");
                        }
                        integration.reload(candidate);
                        climate.reload(candidate);
                        visuals.reload(candidate);
                        seasons.reconfigure(checked.dayTicks(), checked.daysPerSubSeason(),
                                checked.progressWhileEmpty());
                        settings = checked;
                        reconcileWorlds();
                        saveState();
                        reply(sender, "季节配置已重载。群系注册、资源包和起始季节定义变更需要重启。");
                    } catch (RuntimeException failure) {
                        getLogger().log(Level.WARNING, "Configuration reload failed", failure);
                        reply(sender, "重载失败：" + failure.getMessage());
                    } finally {
                        reloadPending.set(false);
                    }
                });
            } catch (Exception failure) {
                reloadPending.set(false);
                getLogger().log(Level.WARNING, "Invalid configuration; existing configuration retained", failure);
                reply(sender, "配置无效，未应用：" + failure.getMessage());
            }
        });
    }

    private void resetClockSource() {
        clockSource = null;
        seasons.reconfigure(settings.dayTicks(), settings.daysPerSubSeason(), settings.progressWhileEmpty());
    }

    private void reconcileWorlds() {
        List<World> worlds = Bukkit.getWorlds();
        if (clockSource != null && Bukkit.getWorld(clockSource.getUID()) != clockSource) resetClockSource();
        if (clockSource == null) {
            String requested = settings.sourceWorld();
            clockSource = worlds.stream().filter(world -> requested.isBlank()
                    ? world.getEnvironment() == World.Environment.NORMAL
                    : matches(world, requested)).findFirst().orElse(null);
            if (clockSource == null) {
                for (UUID id : seasons.snapshots().keySet()) {
                    if (Bukkit.getWorld(id) == null) seasons.unbindWorld(id);
                }
                return;
            }
        }
        Set<UUID> enabled = new HashSet<>();
        for (World world : worlds) {
            boolean selected = settings.enabledWorlds().isEmpty()
                    ? world.getUID().equals(clockSource.getUID())
                    : settings.enabledWorlds().stream().anyMatch(name -> matches(world, name));
            if (selected) {
                enabled.add(world.getUID());
                seasons.bindWorld(world.getUID());
            }
        }
        for (UUID existing : seasons.snapshots().keySet()) {
            if (!enabled.contains(existing)) seasons.unbindWorld(existing);
        }
    }

    private static boolean matches(World world, String name) {
        return world.getName().equals(name) || world.getUID().toString().equalsIgnoreCase(name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        runGlobal(this::reconcileWorlds);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        World unloading = event.getWorld();
        UUID id = unloading.getUID();
        runGlobal(() -> {
            // A newly loaded instance with the same UUID is not a cancelled unload.
            if (Bukkit.getWorld(id) == unloading) return;
            seasons.unbindWorld(id);
            if (clockSource == unloading) resetClockSource();
            reconcileWorlds();
            saveState();
        });
    }

    @Override
    public void onDisable() {
        if (!stopping.compareAndSet(false, true)) return;
        lifecycleEpoch++;
        if (clockTask != null) clockTask.cancel();
        closeSafely("climate", () -> { if (climate != null) climate.close(); });
        closeSafely("visuals", () -> { if (visuals != null) visuals.close(); });
        closeSafely("integration", () -> { if (integration != null) integration.close(); });
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        if (store != null) {
            closeSafely("final state", () -> {
                try {
                    if (seasons != null) store.save(seasons.storedSeasons());
                } finally {
                    store.close();
                }
            });
        }
    }

    private void closeSafely(String component, Runnable close) {
        try {
            close.run();
        } catch (RuntimeException failure) {
            getLogger().log(Level.SEVERE, "Failed to close " + component, failure);
        }
    }
}
