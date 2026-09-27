package dev.ceseasons.climate;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClimateBlockChangesTest {
    @Test
    void eventObservesOriginalBlockBeforeFormationCommit() {
        Fixture f = new Fixture();
        BlockData proposed = data();
        ClimateBlockChanges.apply(f.block, proposed, true, event -> {
            assertInstanceOf(BlockFormEvent.class, event);
            assertSame(f.original, f.current.get());
            assertSame(proposed, ((BlockFormEvent) event).getNewState().getBlockData());
            assertEquals(0, f.writes);
        }, state -> true);
        assertSame(proposed, f.current.get());
        assertEquals(1, f.writes);
        assertTrue(f.physics);
    }

    @Test
    void cancellationOfEitherEventPreventsAllWrites() {
        for (boolean formation : new boolean[]{true, false}) {
            Fixture f = new Fixture();
            ClimateBlockChanges.apply(f.block, data(), formation, event -> {
                assertEquals(formation, event instanceof BlockFormEvent);
                ((Cancellable) event).setCancelled(true);
            }, state -> {
                fail("Cancelled events must not invoke the commit validator");
                return true;
            });
            assertSame(f.original, f.current.get());
            assertEquals(0, f.writes);
        }
    }

    @Test
    void fadeAlsoDispatchesBeforeCommitting() {
        Fixture f = new Fixture();
        BlockData replacement = data();
        ClimateBlockChanges.apply(f.block, replacement, false, event -> {
            assertInstanceOf(BlockFadeEvent.class, event);
            assertSame(f.original, f.current.get());
        }, state -> true);
        assertSame(replacement, f.current.get());
        assertEquals(1, f.writes);
    }

    @Test
    void listenersCanReplaceTheProposedState() {
        Fixture f = new Fixture();
        BlockData replacement = data();
        ClimateBlockChanges.apply(f.block, data(), true, event ->
                ((BlockFormEvent) event).getNewState().setBlockData(replacement), state -> {
            assertSame(replacement, state.getBlockData());
            return true;
        });
        assertSame(replacement, f.current.get());
        assertEquals(1, f.writes);
    }

    @Test
    void reentrantBlockReplacementIsNeverOverwritten() {
        Fixture f = new Fixture();
        BlockData replacement = data();
        ClimateBlockChanges.apply(f.block, data(), true, event -> f.current.set(replacement), state -> true);
        assertSame(replacement, f.current.get());
        assertEquals(0, f.writes);
    }

    @Test
    void epochOrOwnerInvalidationSkipsEvenThePostEventBlockRead() {
        Fixture f = new Fixture();
        ClimateBlockChanges.apply(f.block, data(), false, event -> f.disallowReads = true, state -> false);
        assertSame(f.original, f.current.get());
        assertEquals(0, f.writes);
    }

    private static BlockData data() {
        return proxy(BlockData.class, (object, method, arguments) -> switch (method.getName()) {
            case "equals" -> object == arguments[0];
            case "hashCode" -> System.identityHashCode(object);
            case "toString" -> "TestBlockData@" + System.identityHashCode(object);
            default -> throw new AssertionError("Unexpected block data call " + method);
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Fixture {
        final BlockData original = data();
        final AtomicReference<BlockData> current = new AtomicReference<>(original);
        final AtomicReference<BlockData> proposal = new AtomicReference<>(original);
        int writes;
        boolean physics;
        boolean disallowReads;
        final BlockState state = proxy(BlockState.class, (object, method, arguments) -> switch (method.getName()) {
            case "getBlockData" -> proposal.get();
            case "setBlockData" -> {
                proposal.set((BlockData) arguments[0]);
                yield null;
            }
            default -> throw new AssertionError("Unexpected state call " + method);
        });
        final Block block = proxy(Block.class, (object, method, arguments) -> switch (method.getName()) {
            case "getBlockData" -> {
                assertFalse(disallowReads, "Must recheck owner/lifecycle before another world access");
                yield current.get();
            }
            case "getState" -> state;
            case "setBlockData" -> {
                writes++;
                current.set((BlockData) arguments[0]);
                physics = (Boolean) arguments[1];
                yield null;
            }
            default -> throw new AssertionError("Unexpected block call " + method);
        });
    }
}
