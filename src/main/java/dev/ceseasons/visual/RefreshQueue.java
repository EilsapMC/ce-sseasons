package dev.ceseasons.visual;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded, deduplicated work; key iteration never loads or retains a Bukkit chunk. */
public final class RefreshQueue {
    public record Ticket(UUID player, UUID world, long chunk, long connectionEpoch,
                         long configurationEpoch, long revision, int phase, int tropicalPhase) {
        public boolean matches(UUID currentWorld, long currentConnection, long currentConfiguration,
                               long currentRevision, int currentPhase, int currentTropical) {
            return world.equals(currentWorld) && connectionEpoch == currentConnection
                    && configurationEpoch == currentConfiguration && currentRevision >= revision
                    && phase == currentPhase && tropicalPhase == currentTropical;
        }
    }
    private record Key(UUID player, UUID world, long chunk) {}
    private final int capacity;
    private final LinkedHashMap<Key, Ticket> pending = new LinkedHashMap<>();

    public RefreshQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Queue capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized boolean offer(Ticket ticket) {
        Key key = new Key(ticket.player(), ticket.world(), ticket.chunk());
        if (!pending.containsKey(key) && pending.size() >= capacity) return false;
        pending.put(key, ticket);
        return true;
    }

    public synchronized Ticket poll() {
        var iterator = pending.entrySet().iterator();
        if (!iterator.hasNext()) return null;
        Ticket result = iterator.next().getValue();
        iterator.remove();
        return result;
    }

    public synchronized void removePlayer(UUID player) {
        pending.keySet().removeIf(key -> key.player().equals(player));
    }

    public synchronized int size() { return pending.size(); }
    public synchronized void clear() { pending.clear(); }
}
