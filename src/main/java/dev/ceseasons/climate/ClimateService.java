package dev.ceseasons.climate;

import dev.ceseasons.season.Season;
import dev.ceseasons.season.SeasonSnapshot;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Snow;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Global owns all weather/gamerule access; entity tasks read positions and only
 * sample chunks already owned by that entity's region. No chunk tickets, scans
 * of all loaded chunks, cached Blocks, or region tasks parked on unloaded chunks.
 * The supplied function must be a thread-safe lookup of immutable snapshots;
 * null means the world is disabled. The caller advances the calendar separately.
 */
public final class ClimateService implements Listener, AutoCloseable {
    private static final BlockFace[] HORIZONTAL = {
            BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH
    };

    private final JavaPlugin plugin;
    private final Function<UUID, SeasonSnapshot> snapshots;
    private final BiomeClimate biomes;
    private volatile Run active;
    private boolean registered;

    public ClimateService(JavaPlugin plugin, Function<UUID, SeasonSnapshot> snapshots) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.biomes = new BiomeClimate(plugin.getLogger());
    }

    /** Pure preflight validation for an atomic, whole-plugin reload. */
    public static void validate(FileConfiguration configuration) {
        ClimateSettings.read(Objects.requireNonNull(configuration, "configuration"));
    }

    public synchronized void start(FileConfiguration configuration) {
        reload(configuration);
    }

    public synchronized void reload(FileConfiguration configuration) {
        ClimateSettings settings = ClimateSettings.read(Objects.requireNonNull(configuration, "configuration"));
        stopRun();
        if (!settings.enabled()) {
            return;
        }
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        Run run = new Run(settings);
        active = run;
        try {
            run.ticker = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(run), 1, 1);
        } catch (RuntimeException failure) {
            stopRun();
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        stopRun();
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
    }

    private void stopRun() {
        Run previous = active;
        active = null; // Invalidate the epoch before cancelling queued callbacks.
        if (previous != null) {
            previous.stop();
        }
    }

    private boolean live(Run run) {
        return run != null && run == active && !run.stopped && plugin.isEnabled();
    }

    private void tick(Run run) {
        if (!live(run)) {
            return;
        }
        if (!Bukkit.isGlobalTickThread()) {
            throw new IllegalStateException("Climate weather requires the global region");
        }
        boolean sampleRound = run.ticks++ % run.settings.intervalTicks() == 0;
        Map<UUID, Frame> frames = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            SeasonSnapshot snapshot = snapshots.apply(world.getUID());
            if (snapshot == null || world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            boolean advance = Boolean.TRUE.equals(world.getGameRuleValue(GameRules.ADVANCE_WEATHER));
            if (run.settings.weather() && sampleRound && advance) {
                weather(run, world, snapshot);
            }
            if (!live(run)) {
                return;
            }
            if (Bukkit.getWorld(world.getUID()) != world || snapshots.apply(world.getUID()) == null) {
                continue;
            }
            Integer accumulation = world.getGameRuleValue(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);
            frames.put(world.getUID(), new Frame(world, world.hasStorm(), accumulation == null ? 1 : accumulation));
        }
        run.frames = Map.copyOf(frames);
        if (!sampleRound || !run.settings.snowAndIce()) {
            return;
        }
        run.budget.beginRound();
        // Global collection iteration is safe; position/state reads occur only on entity owners.
        var players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        int start = Math.floorMod(run.cursor, players.size());
        int visited = 0;
        for (; visited < players.size(); visited++) {
            Player player = players.get((start + visited) % players.size());
            SamplingBudget.Permit permit = run.budget.reserve(player.getUniqueId(), run.settings.samplesPerPlayer());
            if (permit == null) {
                continue;
            }
            Work work = new Work(run, permit);
            run.work.put(work, Boolean.TRUE);
            try {
                ScheduledTask task = player.getScheduler().run(plugin, ignored -> {
                    try {
                        samplePlayer(run, player, permit.samples());
                    } catch (RuntimeException failure) {
                        warn(run, failure);
                    } finally {
                        work.finish();
                    }
                }, work::finish);
                work.attach(task);
            } catch (RuntimeException failure) {
                work.finish();
                if (live(run)) {
                    warn(run, failure);
                }
            }
            if (run.budget.exhausted()) {
                visited++;
                break;
            }
        }
        run.cursor = (start + Math.max(visited, 1)) % players.size();
    }

    private void weather(Run run, World world, SeasonSnapshot snapshot) {
        if (snapshot.season() == Season.WINTER && world.isThundering()) {
            world.setThundering(false); // Bukkit fires ThunderChangeEvent; cancellation remains authoritative.
        }
        if (!live(run) || Bukkit.getWorld(world.getUID()) != world
                || !Boolean.TRUE.equals(world.getGameRuleValue(GameRules.ADVANCE_WEATHER))) {
            return;
        }
        snapshot = snapshots.apply(world.getUID());
        if (snapshot == null || world.getClearWeatherDuration() > 0) {
            return; // Preserve explicit /weather clear durations, not just the gamerule.
        }
        if (ClimateRules.shortenRainDelay(snapshot.season(), true, world.hasStorm(), world.getWeatherDuration())) {
            int maximum = ClimateRules.maximumRainDelay(snapshot.season());
            world.setWeatherDuration(ThreadLocalRandom.current().nextInt(ClimateRules.MIN_RAIN_DELAY, maximum + 1));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThunder(ThunderChangeEvent event) {
        Run run = active;
        if (!live(run) || !run.settings.weather() || !event.toThunderState() || !Bukkit.isGlobalTickThread()) {
            return;
        }
        World world = event.getWorld();
        SeasonSnapshot snapshot = snapshots.apply(world.getUID());
        if (world.getEnvironment() == World.Environment.NORMAL && snapshot != null
                && snapshot.season() == Season.WINTER
                && Boolean.TRUE.equals(world.getGameRuleValue(GameRules.ADVANCE_WEATHER))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        Run run = active;
        if (run != null) {
            // Existing frames are immutable; replacing the map invalidates every captured frame for this world.
            Map<UUID, Frame> frames = new HashMap<>(run.frames);
            frames.remove(event.getWorld().getUID());
            run.frames = Map.copyOf(frames);
        }
    }

    private void samplePlayer(Run run, Player player, int samples) {
        if (!live(run) || !Bukkit.isOwnedByCurrentRegion(player) || !player.isOnline() || !player.isTicking()) {
            return;
        }
        Location origin = player.getLocation();
        World world = origin.getWorld();
        Frame frame = run.frames.get(world.getUID());
        if (frame == null || frame.world() != world || snapshots.apply(world.getUID()) == null) {
            return;
        }
        int radius = run.settings.radiusChunks();
        for (int i = 0; i < samples; i++) {
            if (!samePlayerPosition(run, player, origin)) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int chunkX = (origin.getBlockX() >> 4) + random.nextInt(-radius, radius + 1);
            int chunkZ = (origin.getBlockZ() >> 4) + random.nextInt(-radius, radius + 1);
            // No off-owner access or scheduler work is submitted for these skipped candidates.
            if (!ticking(world, chunkX, chunkZ)) {
                continue;
            }
            int x = (chunkX << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            if (!safeColumn(world, x, z)) {
                continue;
            }
            int top = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
            if (top < world.getMinHeight() || top >= world.getMaxHeight() - 1) {
                continue;
            }
            // Capture coordinates only. Block wrappers never outlive this callback.
            sampleBlock(run, player, origin, world.getBlockAt(x, top, z), true);
            if (samePlayerPosition(run, player, origin)) {
                sampleBlock(run, player, origin, world.getBlockAt(x, top + 1, z), false);
            }
        }
    }

    private boolean samePlayerPosition(Run run, Player player, Location origin) {
        if (!live(run) || !Bukkit.isOwnedByCurrentRegion(player) || !player.isOnline() || !player.isTicking()) {
            return false;
        }
        Location now = player.getLocation();
        return now.getWorld() == origin.getWorld() && now.getBlockX() == origin.getBlockX()
                && now.getBlockY() == origin.getBlockY() && now.getBlockZ() == origin.getBlockZ();
    }

    private static boolean ticking(World world, int x, int z) {
        if (!Bukkit.isOwnedByCurrentRegion(world, x, z) || !world.isChunkLoaded(x, z)) {
            return false;
        }
        // Ownership prevents an unload between isChunkLoaded and getChunkAt.
        Chunk.LoadLevel level = world.getChunkAt(x, z).getLoadLevel();
        return level == Chunk.LoadLevel.TICKING || level == Chunk.LoadLevel.ENTITY_TICKING;
    }

    private static boolean safeColumn(World world, int x, int z) {
        // Computed (fiddled) biome lookup crosses quart-cell boundaries up to
        // two blocks away; survival, water adjacency and physics also read neighbours.
        for (int cx = (x - 2) >> 4; cx <= (x + 2) >> 4; cx++) {
            for (int cz = (z - 2) >> 4; cz <= (z + 2) >> 4; cz++) {
                if (!ticking(world, cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void sampleBlock(Run run, Player player, Location origin, Block block, boolean surface) {
        LocalClimate climate = climate(run, block);
        if (climate == null || !climate.affected()) {
            return;
        }
        Material type = block.getType();
        if ((type == Material.SNOW || type == Material.ICE)
                && ClimateRules.shouldThaw(climate.temperature(), true)) {
            change(run, player, origin, block, (type == Material.ICE ? Material.WATER : Material.AIR).createBlockData(),
                    false, climate.snapshot());
            return;
        }
        if (surface && type == Material.WATER && canFreeze(block, climate)) {
            change(run, player, origin, block, Material.ICE.createBlockData(), true, climate.snapshot());
            return;
        }
        if (type != Material.SNOW && !type.isAir()) {
            return;
        }
        Frame frame = run.frames.get(block.getWorld().getUID());
        if (frame == null || frame.snowHeight() <= 0) {
            return;
        }
        Snow snow = (Snow) Material.SNOW.createBlockData();
        if (type == Material.SNOW) {
            int current = ((Snow) block.getBlockData()).getLayers();
            if (current >= Math.min(frame.snowHeight(), snow.getMaximumLayers())) {
                return;
            }
            snow.setLayers(current + 1);
        }
        if (ClimateRules.canSnow(climate.temperature(), block.getLightFromBlocks(),
                climate.precipitation(), snow.isSupported(block))) {
            change(run, player, origin, block, snow, true, climate.snapshot());
        }
    }

    private static boolean canFreeze(Block block, LocalClimate climate) {
        BlockData data = block.getBlockData();
        boolean source = block.getType() == Material.WATER && data instanceof Levelled water && water.getLevel() == 0;
        boolean surrounded = true;
        for (BlockFace face : HORIZONTAL) {
            Block neighbour = block.getRelative(face);
            BlockData adjacent = neighbour.getBlockData();
            boolean water = neighbour.getType() == Material.WATER
                    || adjacent instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()
                    || neighbour.getType() == Material.KELP || neighbour.getType() == Material.KELP_PLANT
                    || neighbour.getType() == Material.SEAGRASS || neighbour.getType() == Material.TALL_SEAGRASS
                    || neighbour.getType() == Material.BUBBLE_COLUMN;
            surrounded &= water;
        }
        return ClimateRules.canFreeze(climate.temperature(), block.getLightFromBlocks(), source, surrounded);
    }

    private LocalClimate climate(Run run, Block block) {
        if (!live(run) || !run.settings.snowAndIce() || !safeColumn(block.getWorld(), block.getX(), block.getZ())) {
            return null;
        }
        Frame frame = run.frames.get(block.getWorld().getUID());
        SeasonSnapshot snapshot = snapshots.apply(block.getWorld().getUID());
        if (frame == null || frame.world() != block.getWorld() || snapshot == null) {
            return null;
        }
        var biome = block.getComputedBiome();
        String key = biome.getKey().toString();
        boolean tropical = run.settings.tropicalBiomes().contains(key);
        boolean blacklisted = run.settings.blacklistedBiomes().contains(key);
        if (blacklisted) {
            return null;
        }
        BiomeClimate.Properties properties = biomes.read(biome);
        if (properties == null) {
            return null;
        }
        double base = properties.baseTemperature();
        double local = block.getTemperature();
        double temperature = ClimateRules.temperature(base, local, snapshot.temperatureOffset(), tropical, false);
        boolean precipitation = ClimateRules.precipitation(frame.storm(), properties.precipitation(),
                tropical, snapshot.tropicalSeason());
        return new LocalClimate(snapshot, local, temperature, tropical,
                ClimateRules.affected(base, tropical, false), precipitation);
    }

    /** Query on the block's owning region only; dry/wet rules never start a world storm. */
    public boolean hasPrecipitation(Block block) {
        LocalClimate climate = climate(active, block);
        return climate != null && climate.precipitation();
    }

    private void change(Run run, Player player, Location origin, Block block, BlockData proposed,
                        boolean formation, SeasonSnapshot expected) {
        if (!samePlayerPosition(run, player, origin)) {
            return;
        }
        ClimateBlockChanges.apply(block, proposed, formation,
                event -> Bukkit.getPluginManager().callEvent(event), state -> {
                    if (!samePlayerPosition(run, player, origin)
                            || !safeColumn(block.getWorld(), block.getX(), block.getZ())) {
                        return false;
                    }
                    LocalClimate after = climate(run, block);
                    if (after == null || !after.affected() || after.snapshot().subSeason() != expected.subSeason()
                            || after.snapshot().tropicalSeason() != expected.tropicalSeason()) {
                        return false;
                    }
                    if (formation && proposed.getMaterial() == Material.ICE && !canFreeze(block, after)) {
                        return false;
                    }
                    if (formation && proposed.getMaterial() == Material.SNOW) {
                        Frame frame = run.frames.get(block.getWorld().getUID());
                        if (frame == null || frame.snowHeight() <= 0
                                || !ClimateRules.canSnow(after.temperature(), block.getLightFromBlocks(),
                                after.precipitation(), Material.SNOW.createBlockData().isSupported(block))) {
                            return false;
                        }
                        if (state.getBlockData() instanceof Snow snow
                                && (snow.getLayers() > frame.snowHeight() || !snow.isSupported(block))) {
                            return false;
                        }
                    }
                    return formation || ClimateRules.shouldThaw(after.temperature(), after.affected());
                });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        Material formed = event.getNewState().getType();
        if (formed != Material.SNOW && formed != Material.ICE) {
            return;
        }
        Run run = active;
        Block block = event.getBlock();
        LocalClimate climate = climate(run, block);
        if (climate == null) {
            return;
        }
        if (formed == Material.SNOW && climate.tropical() && !climate.precipitation()) {
            event.setCancelled(true);
            return;
        }
        if (!climate.affected()) {
            return;
        }
        if (formed == Material.ICE && !canFreeze(block, climate)) {
            event.setCancelled(true);
        } else if (formed == Material.SNOW && (!block.getType().isAir() && block.getType() != Material.SNOW
                || !ClimateRules.canSnow(climate.temperature(), block.getLightFromBlocks(),
                climate.precipitation(), event.getNewState().getBlockData().isSupported(block)))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Run run = active;
        Block block = event.getBlock();
        if (!live(run) || !run.settings.snowAndIce()
                || !safeColumn(block.getWorld(), block.getX(), block.getZ())) {
            return;
        }
        Material type = block.getType();
        Material next = event.getNewState().getType();
        if (type != Material.SNOW && type != Material.ICE
                || next != Material.AIR && next != Material.WATER) {
            return; // Never change packed/blue/frosted ice or unrelated fades.
        }
        LocalClimate climate = climate(active, block);
        if (climate == null) {
            return;
        }
        // Snow's vanilla threshold is >11. For ice conservatively preserve every
        // fade with emitted block light: the API does not expose light dampening.
        boolean lightDriven = block.getLightFromBlocks() > (type == Material.SNOW ? 11 : 0);
        boolean supported = type != Material.SNOW || block.getBlockData().isSupported(block);
        if (ClimateRules.protectClimateFade(climate.vanillaTemperature(), climate.temperature(), climate.affected(),
                lightDriven, supported)) {
            event.setCancelled(true);
        }
    }

    private void warn(Run run, RuntimeException failure) {
        if (run.warned.compareAndSet(false, true)) {
            plugin.getLogger().log(Level.WARNING, "Climate sampling failed; this round is skipped", failure);
        }
    }

    private record Frame(World world, boolean storm, int snowHeight) {
    }

    private record LocalClimate(SeasonSnapshot snapshot, double vanillaTemperature, double temperature,
                                boolean tropical, boolean affected, boolean precipitation) {
    }

    private static final class Run {
        final ClimateSettings settings;
        final SamplingBudget budget;
        final Map<Work, Boolean> work = new ConcurrentHashMap<>();
        final AtomicBoolean warned = new AtomicBoolean();
        volatile Map<UUID, Frame> frames = Map.of();
        volatile boolean stopped;
        volatile ScheduledTask ticker;
        long ticks;
        int cursor;

        Run(ClimateSettings settings) {
            this.settings = settings;
            this.budget = new SamplingBudget(settings.maxSamplesPerRun());
        }

        void stop() {
            stopped = true;
            frames = Map.of();
            if (ticker != null) {
                ticker.cancel();
            }
            work.keySet().forEach(Work::cancel);
        }
    }

    private static final class Work {
        final Run run;
        final SamplingBudget.Permit permit;
        private boolean finished;
        private ScheduledTask task;

        Work(Run run, SamplingBudget.Permit permit) {
            this.run = run;
            this.permit = permit;
        }

        synchronized void attach(ScheduledTask scheduled) {
            task = scheduled;
            if (scheduled == null || finished || run.stopped) {
                cancel();
            }
        }

        synchronized void cancel() {
            if (task != null) {
                task.cancel();
            }
            finish();
        }

        synchronized void finish() {
            if (!finished) {
                finished = true;
                permit.close();
                run.work.remove(this);
            }
        }
    }
}
