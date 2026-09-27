package dev.ceseasons.integration;

import dev.ceseasons.season.Season;
import dev.ceseasons.season.SeasonSnapshot;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.behavior.*;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelWriterProxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockRedstoneEvent;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** mode and power are CE block-state properties, persisted by CE chunk storage. */
public final class SeasonSensorBehavior extends BukkitBlockBehavior implements EntityBlock {
    private static final Season[] MODES = {Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER};
    private final IntegerProperty mode;
    private final IntegerProperty power;
    private final Function<UUID, SeasonSnapshot> snapshots;
    private final BooleanSupplier enabled;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final java.util.Set<Controller> controllers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    SeasonSensorBehavior(BlockDefinition block, ConfigSection section, Function<UUID, SeasonSnapshot> snapshots, BooleanSupplier enabled, org.bukkit.plugin.java.JavaPlugin plugin) {
        super(block);
        this.mode = (IntegerProperty) BlockBehaviorFactory.getProperty(section.path(), block, "mode", Integer.class);
        this.power = (IntegerProperty) BlockBehaviorFactory.getProperty(section.path(), block, "power", Integer.class);
        if (mode.min != 0 || mode.max != 3 || power.min != 0 || power.max != 15)
            throw new IllegalArgumentException("season_sensor requires mode=0..3 and power=0..15 properties");
        this.snapshots = snapshots;
        this.enabled = enabled;
        this.plugin = plugin;
    }

    @Override public int getSignal(Object self, Object[] args) {
        return BlockStateUtils.getOptionalCustomBlockState(args[0]).map(s -> s.get(power)).orElse(0);
    }
    @Override public int getDirectSignal(Object self, Object[] args) { return 0; }
    @Override public boolean isSignalSource(Object self, Object[] args) { return true; }

    @Override public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        if (!enabled.getAsBoolean() || context.getPlayer() == null || !ItemUtils.isEmpty(context.getItem())) return InteractionResult.PASS;
        Player player = (Player) context.getPlayer().platformPlayer();
        var pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        Location at = new Location(world, pos.x, pos.y, pos.z);
        if (!Bukkit.isOwnedByCurrentRegion(at) || !Bukkit.isOwnedByCurrentRegion(player)) return InteractionResult.SUCCESS_AND_CANCEL;
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(player, Flag.INTERACT, at)) return InteractionResult.SUCCESS_AND_CANCEL;
        update(context.getLevel().minecraftWorld(), world, pos, state, (state.get(mode) + 1) % MODES.length);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private void update(Object level, World world, net.momirealms.craftengine.core.world.BlockPos pos, ImmutableBlockState old, int nextMode) {
        Location location = new Location(world, pos.x, pos.y, pos.z);
        if (!Bukkit.isOwnedByCurrentRegion(location)) return;
        SeasonSnapshot snapshot = snapshots.apply(world.getUID());
        int nextPower = !enabled.getAsBoolean() || !world.hasSkyLight() || snapshot == null ? 0 : snapshot.sensorPower(MODES[nextMode]);
        nextPower = Math.clamp(nextPower, 0, 15);
        if (old.get(power) != nextPower) {
            BlockRedstoneEvent event = new BlockRedstoneEvent(location.getBlock(), old.get(power), nextPower);
            Bukkit.getPluginManager().callEvent(event);
            nextPower = Math.clamp(event.getNewCurrent(), 0, 15);
        }
        if (nextPower == old.get(power) && nextMode == old.get(mode)) return;
        Object nmsPos = LocationUtils.toBlockPos(pos);
        Object state = old.with(mode, nextMode).with(power, nextPower).customBlockState().minecraftState();
        LevelWriterProxy.INSTANCE.setBlock(level, nmsPos, state, UpdateFlags.UPDATE_ALL);
        LevelAccessorProxy.INSTANCE.updateNeighborsAt(level, nmsPos, BlockStateUtils.getBlockOwner(state));
    }

    @Override public void affectNeighborsAfterRemoval(Object self, Object[] args) {
        if (getSignal(self, args) > 0) LevelAccessorProxy.INSTANCE.updateNeighborsAt(args[1], args[2], self);
    }
    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) { return new Controller(entity); }
    @Override public void initControllerId(int id) {}

    void start() { controllers.forEach(Controller::start); }
    void close() { controllers.forEach(Controller::stop); }

    private final class Controller extends BlockEntityController {
        private io.papermc.paper.threadedregions.scheduler.ScheduledTask task;
        private boolean loaded;
        Controller(BlockEntity entity) { super(entity); }
        @Override public synchronized void onLoad() { loaded = true; controllers.add(this); start(); }
        @Override public synchronized void onUnload() { loaded = false; stop(); controllers.remove(this); }
        @Override public void onRemove() { onUnload(); }
        synchronized void stop() { if (task != null) { task.cancel(); task = null; } }
        synchronized void start() {
            if (!loaded || task != null || !enabled.getAsBoolean()) return;
            CEWorld world = blockEntity.world();
            if (world == null) return;
            var pos = blockEntity.pos();
            World bukkit = (World) world.world().platformWorld();
            Location at = new Location(bukkit, pos.x, pos.y, pos.z);
            // Use a Location overload: CE FoliaCEWorld passes chunk coordinates to its block-coordinate scheduler.
            // This task belongs to this plugin, counts actual region ticks, and is cancelled on CE unload.
            task = Bukkit.getRegionScheduler().runAtFixedRate(plugin, at, scheduled -> {
                if (!enabled.getAsBoolean() || !bukkit.isChunkLoaded(pos.x >> 4, pos.z >> 4)) { stop(); return; }
                var current = net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(at.getBlock());
                if (current == null || current.owner() != blockEntity.blockState().owner()) { stop(); return; }
                update(world.world().minecraftWorld(), bukkit, pos, current, current.get(mode));
            }, 20, 20);
        }
    }
}
