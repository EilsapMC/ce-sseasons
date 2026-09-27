package dev.ceseasons.integration;

import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import java.util.function.Consumer;

/** Composition, not inheritance from final CropBlockBehavior. No gate+crop behavior chain. */
abstract class ForwardingBlockBehavior extends BlockBehavior {
    protected final BlockBehavior delegate;
    ForwardingBlockBehavior(BlockDefinition block, BlockBehavior delegate) { super(block); this.delegate = delegate; }
    @Override public <T> void let(Class<T> type, Consumer<T> action) {
        if (type.isInstance(this)) action.accept(type.cast(this)); else delegate.let(type, action);
    }
    @Override public <T> T getFirst(Class<T> type) { return type.isInstance(this) ? type.cast(this) : delegate.getFirst(type); }
    @Override public Object rotate(Object b, Object[] a) { return delegate.rotate(b,a); }
    @Override public Object mirror(Object b, Object[] a) { return delegate.mirror(b,a); }
    @Override public Object updateShape(Object b, Object[] a) { return delegate.updateShape(b,a); }
    @Override public void neighborChanged(Object b, Object[] a) { delegate.neighborChanged(b,a); }
    @Override public void tick(Object b, Object[] a) { delegate.tick(b,a); }
    @Override public void randomTick(Object b, Object[] a) { delegate.randomTick(b,a); }
    @Override public void onPlace(Object b, Object[] a) { delegate.onPlace(b,a); }
    @Override public boolean canSurvive(Object b, Object[] a) { return delegate.canSurvive(b,a); }
    @Override public boolean isPathFindable(Object b, Object[] a) { return delegate.isPathFindable(b,a); }
    @Override public boolean hasAnalogOutputSignal(Object b, Object[] a) { return delegate.hasAnalogOutputSignal(b,a); }
    @Override public int getAnalogOutputSignal(Object b, Object[] a) { return delegate.getAnalogOutputSignal(b,a); }
    @Override public void preExplosionHit(Object b, Object[] a) { delegate.preExplosionHit(b,a); }
    @Override public void postExplosionHit(Object b, Object[] a) { delegate.postExplosionHit(b,a); }
    @Override public void entityInside(Object b, Object[] a) { delegate.entityInside(b,a); }
    @Override public void affectNeighborsAfterRemoval(Object b, Object[] a) { delegate.affectNeighborsAfterRemoval(b,a); }
    @Override public int getSignal(Object b, Object[] a) { return delegate.getSignal(b,a); }
    @Override public int getDirectSignal(Object b, Object[] a) { return delegate.getDirectSignal(b,a); }
    @Override public boolean isSignalSource(Object b, Object[] a) { return delegate.isSignalSource(b,a); }
    @Override public Object playerWillDestroy(Object b, Object[] a) { return delegate.playerWillDestroy(b,a); }
    @Override public void spawnAfterBreak(Object b, Object[] a) { delegate.spawnAfterBreak(b,a); }
    @Override public void stepOn(Object b, Object[] a) { delegate.stepOn(b,a); }
    @Override public void onProjectileHit(Object b, Object[] a) { delegate.onProjectileHit(b,a); }
    @Override public void placeMultiState(Object b, Object[] a) { delegate.placeMultiState(b,a); }
    @Override public void fallOn(Object b, Object[] a) { delegate.fallOn(b,a); }
    @Override public void updateEntityMovementAfterFallOn(Object b, Object[] a) { delegate.updateEntityMovementAfterFallOn(b,a); }
    @Override public boolean triggerEvent(Object b, Object[] a) { return delegate.triggerEvent(b,a); }
    @Override public void attack(Object b, Object[] a) { delegate.attack(b,a); }
    @Override public void handlePrecipitation(Object b, Object[] a) { delegate.handlePrecipitation(b,a); }
    @Override public boolean canPlaceMultiState(WorldAccessor w, BlockPos p, ImmutableBlockState s) { return delegate.canPlaceMultiState(w,p,s); }
    @Override public boolean hasMultiState(ImmutableBlockState s) { return delegate.hasMultiState(s); }
    @Override public Item itemToPickup(World w, BlockPos p, ImmutableBlockState s, Player player) { return delegate.itemToPickup(w,p,s,player); }
    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext c, ImmutableBlockState s) { return delegate.updateStateForPlacement(c,s); }
    @Override public boolean canBeReplaced(BlockPlaceContext c, ImmutableBlockState s) { return delegate.canBeReplaced(c,s); }
    @Override public InteractionResult useOnBlock(UseOnContext c, ImmutableBlockState s) { return delegate.useOnBlock(c,s); }
    @Override public InteractionResult useWithoutItem(UseOnContext c, ImmutableBlockState s) { return delegate.useWithoutItem(c,s); }
    @Override public boolean canUseOnBlockIfSecondaryUseActive(UseOnContext c, ImmutableBlockState s) { return delegate.canUseOnBlockIfSecondaryUseActive(c,s); }
}
