package dev.ceseasons.climate;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Event-first commit; no server static access, allowing protection contract tests. */
final class ClimateBlockChanges {
    private ClimateBlockChanges() {
    }

    static void apply(Block block, BlockData proposed, boolean formation,
                      Consumer<Event> dispatch, Predicate<BlockState> stillValid) {
        BlockData before = block.getBlockData();
        BlockState state = block.getState();
        state.setBlockData(proposed);
        if (formation) {
            BlockFormEvent event = new BlockFormEvent(block, state);
            dispatch.accept(event);
            if (event.isCancelled()) {
                return;
            }
        } else {
            BlockFadeEvent event = new BlockFadeEvent(block, state);
            dispatch.accept(event);
            if (event.isCancelled()) {
                return;
            }
        }
        // Recheck owner/epoch before reading the source again: a listener may
        // teleport, unload, reload, or replace the source block reentrantly.
        if (!stillValid.test(state) || !block.getBlockData().equals(before)) {
            return;
        }
        // update(false) rejects air->snow / water->ice. Source equality is the
        // guard instead; commit the event's proposal, not the original argument.
        block.setBlockData(state.getBlockData(), true);
    }
}
