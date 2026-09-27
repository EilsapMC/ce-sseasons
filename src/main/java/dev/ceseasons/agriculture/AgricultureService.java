package dev.ceseasons.agriculture;

import dev.ceseasons.season.SeasonSnapshot;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** All block reads occur on event / CE block owners, never async or global schedulers. */
public final class AgricultureService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Function<UUID, SeasonSnapshot> snapshots;
    private volatile AgricultureSettings settings;
    private volatile boolean running;
    private final Map<Position, Attempt> attempts = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> delegated = ThreadLocal.withInitial(() -> 0);
    /** Marker is discovered on the current block definition, never a stale reload-era ID set. */
    public interface SeasonGated {}
    private final Set<Class<?>> captureWarnings = ConcurrentHashMap.newKeySet();
    private final Map<Class<?>, Optional<Method>> regionDataMethods = new ConcurrentHashMap<>();
    private final Map<Class<?>, Field> captureFields = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> skyMethods = new ConcurrentHashMap<>();
    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private record Position(UUID world, int x, int y, int z) {
        static Position of(Block b) { return new Position(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }
    }
    private record Attempt(UUID player, EquipmentSlot hand, ItemStack item, boolean dispenser) {}

    public AgricultureService(JavaPlugin plugin, Function<UUID, SeasonSnapshot> snapshots) {
        this.plugin = plugin; this.snapshots = snapshots;
        settings = AgricultureSettings.parse(new org.bukkit.configuration.file.YamlConfiguration());
    }
    public AgricultureSettings settings() { return settings; }
    public void reload(FileConfiguration config) { settings = AgricultureSettings.parse(config); }
    public void start() {
        if (running) return; running = true; Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    private boolean wrapped(Block block) {
        var state = CraftEngineBlocks.getCustomBlockState(block);
        return state != null && !state.isEmpty() && state.behavior().getFirst(SeasonGated.class) != null;
    }
    public void delegated(Runnable action) {
        int depth = delegated.get(); delegated.set(depth + 1);
        try { action.run(); } finally { if (depth == 0) delegated.remove(); else delegated.set(depth); }
    }
    public boolean inDelegatedCall() { return delegated.get() > 0; }

    public FertilityRules.Decision decide(Block block, String id) {
        AgricultureSettings c = settings;
        if (!running || !c.enabled() || !Bukkit.isOwnedByCurrentRegion(block.getLocation())) return FertilityRules.Decision.ALLOW;
        Set<dev.ceseasons.season.Season> seasons = c.crops().get(id);
        if (seasons == null) return FertilityRules.Decision.ALLOW;
        World world = block.getWorld();
        String biome = block.getBiome().getKey().toString();
        if (c.excludedWorlds().contains(world.getName()) || c.excludedWorlds().contains(world.getUID().toString())
                || c.blacklistedBiomes().contains(biome)) return FertilityRules.Decision.ALLOW;
        SeasonSnapshot snapshot = snapshots.apply(world.getUID());
        if (snapshot == null) return FertilityRules.Decision.ALLOW;
        boolean greenhouse = greenhouse(block);
        boolean underground = block.getY() < c.undergroundY() && !canSeeSky(block);
        FertilityRules.Environment environment = new FertilityRules.Environment(snapshot.season(), c.tropicalBiomes().contains(biome),
                block.getTemperature() < 0.15, greenhouse, underground);
        if (c.infertileBiomes().contains(biome) && !greenhouse && !underground)
            return c.mode() == FertilityRules.OffSeason.WITHER ? FertilityRules.Decision.WITHER : FertilityRules.Decision.DENY;
        return FertilityRules.decide(seasons, environment, c.mode(), ThreadLocalRandom.current().nextInt(c.chance()), c.chance());
    }
    private boolean greenhouse(Block block) {
        AgricultureSettings c = settings;
        for (int y = block.getY() + 1, end = Math.min(block.getY() + c.greenhouseHeight(), block.getWorld().getMaxHeight() - 1); y <= end; y++) {
            Block above = block.getWorld().getBlockAt(block.getX(), y, block.getZ());
            if (c.greenhouseBlocks().contains(id(above))) return true;
            if (above.getType().isOccluding()) return false;
        }
        return false;
    }
    private String id(Block block) {
        var custom = CraftEngineBlocks.getCustomBlockState(block);
        return custom == null || custom.isEmpty() ? block.getType().getKey().toString() : custom.owner().value().id().asString();
    }
    private boolean canSeeSky(Block block) {
        Object level = BukkitAdaptor.adapt(block.getWorld()).minecraftWorld();
        Object pos = net.momirealms.craftengine.bukkit.util.LocationUtils.toBlockPos(
                new net.momirealms.craftengine.core.world.BlockPos(block.getX(), block.getY(), block.getZ()));
        try {
            Method method = skyMethods.get(level.getClass());
            if (method == null) {
                method = level.getClass().getMethod("canSeeSky", net.momirealms.craftengine.proxy.minecraft.core.BlockPosProxy.CLASS);
                skyMethods.put(level.getClass(), method);
            }
            return (boolean) method.invoke(level, pos);
        } catch (ReflectiveOperationException failure) {
            // Do not falsely exempt an underground crop when the native bridge is unavailable.
            return true;
        }
    }
    private Block growthOrigin(BlockGrowEvent event) {
        Block block = event.getBlock();
        Material result = event.getNewState().getType();
        if (result == Material.MELON || result == Material.PUMPKIN) {
            Material stem = result == Material.MELON ? Material.MELON_STEM : Material.PUMPKIN_STEM;
            for (BlockFace face : HORIZONTAL) {
                Block neighbor = block.getRelative(face);
                if (Bukkit.isOwnedByCurrentRegion(neighbor.getLocation()) && neighbor.getType() == stem) return neighbor;
            }
        }
        if (!settings.crops().containsKey(id(block)) && (result == Material.SUGAR_CANE || result == Material.CACTUS || result == Material.BAMBOO)) {
            Block below = block.getRelative(BlockFace.DOWN);
            if (below.getType() == result) return below;
        }
        return block;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void grow(BlockGrowEvent event) {
        if (inDelegatedCall() || capturingBoneMeal(event.getBlock().getWorld())) return;
        Block block = growthOrigin(event);
        String id = id(block);
        if (wrapped(block)) return; // only wrapper owns CE probabilities, including raw stem setBlock
        FertilityRules.Decision decision = decide(block, id);
        if (decision != FertilityRules.Decision.ALLOW) { event.setCancelled(true); if (decision == FertilityRules.Decision.WITHER) wither(block); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void structure(StructureGrowEvent event) {
        Block origin = event.getLocation().getBlock();
        Attempt attempt = attempts.get(Position.of(origin));
        // Both player and dispenser tree growth are finalized by BlockFertilizeEvent.
        if (inDelegatedCall() || event.isFromBonemeal() || attempt != null && attempt.dispenser()) return;
        String id = id(origin);
        if (wrapped(origin)) return;
        FertilityRules.Decision decision = decide(origin, id);
        if (decision != FertilityRules.Decision.ALLOW) { event.setCancelled(true); if (decision == FertilityRules.Decision.WITHER) wither(origin); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberHand(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() == null
                || event.useItemInHand() == Event.Result.DENY || event.useInteractedBlock() == Event.Result.DENY
                || event.getItem() == null || event.getItem().getType() != Material.BONE_MEAL) return;
        remember(event.getClickedBlock(), new Attempt(event.getPlayer().getUniqueId(), event.getHand(), event.getItem().clone(), false));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberDispenser(BlockDispenseEvent event) {
        if (event.getItem().getType() != Material.BONE_MEAL) return;
        if (event.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional directional) {
            Block target = event.getBlock().getRelative(directional.getFacing());
            if (Bukkit.isOwnedByCurrentRegion(target.getLocation())) remember(target, new Attempt(null, null, null, true));
        }
    }
    private void remember(Block target, Attempt attempt) {
        Position key = Position.of(target); attempts.put(key, attempt);
        Bukkit.getRegionScheduler().runDelayed(plugin, target.getLocation(), t -> attempts.remove(key, attempt), 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fertilize(BlockFertilizeEvent event) {
        Block block = event.getBlock();
        Attempt attempt = attempts.remove(Position.of(block));
        String id = id(block);
        if (inDelegatedCall() || wrapped(block)) return;
        FertilityRules.Decision decision = decide(block, id);
        if (decision == FertilityRules.Decision.ALLOW) return;
        // Earlier protection cancellation never reaches this handler; never charge those attempts.
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player != null && attempt != null && player.getUniqueId().equals(attempt.player())
                && Bukkit.isOwnedByCurrentRegion(player) && player.getGameMode() != GameMode.CREATIVE) {
            ItemStack item = player.getInventory().getItem(attempt.hand());
            if (item.getType() == Material.BONE_MEAL && item.isSimilar(attempt.item()) && item.getAmount() == attempt.item().getAmount()) {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(attempt.hand(), item);
            }
        }
        if (decision == FertilityRules.Decision.WITHER) wither(block);
    }

    public void wither(Block block) {
        if (settings.unbreakableCrops().contains(id(block))) return;
        String expected = block.getBlockData().getAsString();
        Bukkit.getRegionScheduler().runDelayed(plugin, block.getLocation(), task -> {
            if (!running || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                    || !expected.equals(block.getBlockData().getAsString())) return;
            if (settings.unbreakableCrops().contains(id(block))) return;
            // Deferred until capture transactions finish; protection can veto this explicit fade.
            org.bukkit.block.BlockState faded = block.getState();
            faded.setType(Material.AIR);
            BlockFadeEvent event = new BlockFadeEvent(block, faded);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled() && expected.equals(block.getBlockData().getAsString()))
                event.getNewState().update(true, true);
        }, 1);
    }

    /** Paper stores this on Level; Folia stores it on the current owner region world data. */
    public boolean capturingBoneMeal(World world) {
        Object level = BukkitAdaptor.adapt(world).minecraftWorld();
        try {
            Object holder = level;
            Optional<Method> region = regionDataMethods.get(level.getClass());
            if (region == null) {
                try { region = Optional.of(level.getClass().getMethod("getCurrentWorldData")); }
                catch (NoSuchMethodException paper) { region = Optional.empty(); }
                regionDataMethods.put(level.getClass(), region);
            }
            if (region.isPresent()) holder = region.get().invoke(level);
            Field field = captureFields.get(holder.getClass());
            if (field == null) { field = holder.getClass().getField("captureTreeGeneration"); captureFields.put(holder.getClass(), field); }
            return field.getBoolean(holder);
        } catch (ReflectiveOperationException failure) {
            if (captureWarnings.add(level.getClass())) plugin.getLogger().severe("Bone meal capture bridge unavailable; vanilla growth interception disabled to avoid double rolls: " + failure);
            return true; // explicit fail-open for natural growth, fertilizer event remains supported
        }
    }
    @Override public void close() { running = false; HandlerList.unregisterAll(this); attempts.clear(); delegated.remove(); }
}
