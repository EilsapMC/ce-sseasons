package dev.ceseasons.integration;

import dev.ceseasons.agriculture.AgricultureService;
import dev.ceseasons.agriculture.FertilityRules;
import net.momirealms.craftengine.bukkit.block.behavior.CropBlockBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.StemBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.*;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

final class SeasonalGrowthBehavior extends ForwardingBlockBehavior implements RandomTickBlock, BonemealableBlock, PathFindingBlock, AgricultureService.SeasonGated {
    private final AgricultureService agriculture;
    private final boolean stem;
    private final String cropId;
    SeasonalGrowthBehavior(BlockDefinition block, ConfigSection config, AgricultureService agriculture, boolean stem) {
        super(block, stem ? StemBlockBehavior.FACTORY.create(block, config) : CropBlockBehavior.FACTORY.create(block, config));
        this.agriculture = agriculture; this.stem = stem; this.cropId = block.id().asString();
    }
    @Override public boolean canRandomlyTick(ImmutableBlockState state) { return ((RandomTickBlock) delegate).canRandomlyTick(state); }
    @Override public net.momirealms.craftengine.core.entity.player.InteractionResult useOnBlock(
            net.momirealms.craftengine.core.world.context.UseOnContext context, ImmutableBlockState state) {
        var item = context.getItem();
        if (context.getPlayer() != null && !net.momirealms.craftengine.core.util.ItemUtils.isEmpty(item)
                && item.vanillaId().equals(net.momirealms.craftengine.core.item.ItemKeys.BONE_MEAL)) {
            var pos = context.getClickedPos();
            var player = (org.bukkit.entity.Player) context.getPlayer().platformPlayer();
            var at = new Location((World) context.getLevel().platformWorld(), pos.x, pos.y, pos.z);
            if (!Bukkit.isOwnedByCurrentRegion(at) || !Bukkit.isOwnedByCurrentRegion(player)
                    || !net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine.instance().antiGriefProvider()
                    .test(player, net.momirealms.craftengine.libraries.antigrieflib.Flag.INTERACT, at))
                return net.momirealms.craftengine.core.entity.player.InteractionResult.SUCCESS_AND_CANCEL;
        }
        return delegate.useOnBlock(context, state);
    }
    private boolean allow(Object level, Object pos) {
        World world = LevelProxy.INSTANCE.getWorld(level);
        int x = Vec3iProxy.INSTANCE.getX(pos), y = Vec3iProxy.INSTANCE.getY(pos), z = Vec3iProxy.INSTANCE.getZ(pos);
        Location at = new Location(world, x, y, z);
        if (!Bukkit.isOwnedByCurrentRegion(at)) return false;
        if (stem && (!Bukkit.isOwnedByCurrentRegion(new Location(world, x+1, y, z))
                || !Bukkit.isOwnedByCurrentRegion(new Location(world, x-1, y, z))
                || !Bukkit.isOwnedByCurrentRegion(new Location(world, x, y, z+1))
                || !Bukkit.isOwnedByCurrentRegion(new Location(world, x, y, z-1)))) return false;
        if (agriculture.inDelegatedCall()) return true; // performBonemeal may invoke stem randomTick recursively
        Block block = at.getBlock();
        FertilityRules.Decision decision = agriculture.decide(block, cropId);
        if (decision == FertilityRules.Decision.WITHER) agriculture.wither(block);
        return decision == FertilityRules.Decision.ALLOW;
    }
    @Override public void randomTick(Object self, Object[] args) {
        if (allow(args[1], args[2])) agriculture.delegated(() -> delegate.randomTick(self, args));
    }
    @Override public boolean isValidBonemealTarget(Object self, Object[] args) {
        return ((BonemealableBlock) delegate).isValidBonemealTarget(self, args);
    }
    @Override public boolean isBonemealSuccess(Object self, Object[] args) {
        // Vanilla consumes one on false success (creative restores it); no manual protection bypass.
        return ((BonemealableBlock) delegate).isBonemealSuccess(self, args) && allow(args[0], args[2]);
    }
    @Override public void performBonemeal(Object self, Object[] args) {
        // No second roll. CE's crop can fire BlockGrowEvent; stem writes directly.
        agriculture.delegated(() -> ((BonemealableBlock) delegate).performBonemeal(self, args));
    }
}
