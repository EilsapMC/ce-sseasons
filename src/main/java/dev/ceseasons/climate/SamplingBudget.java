package dev.ceseasons.climate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One shared budget for every player/world, including work spanning rounds. */
final class SamplingBudget {
    private final int maximum;
    private final Map<UUID, Permit> pending = new HashMap<>();
    private int remaining;
    private int outstanding;

    SamplingBudget(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        this.maximum = maximum;
    }

    synchronized void beginRound() {
        remaining = maximum;
    }

    synchronized Permit reserve(UUID player, int requested) {
        if (pending.containsKey(player)) {
            return null;
        }
        int count = Math.min(requested, Math.min(remaining, maximum - outstanding));
        if (count <= 0) {
            return null;
        }
        Permit permit = new Permit(player, count);
        pending.put(player, permit);
        remaining -= count;
        outstanding += count;
        return permit;
    }

    synchronized int outstanding() {
        return outstanding;
    }

    synchronized boolean exhausted() {
        return remaining == 0 || outstanding >= maximum;
    }

    final class Permit implements AutoCloseable {
        private final UUID player;
        private final int samples;

        private Permit(UUID player, int samples) {
            this.player = player;
            this.samples = samples;
        }

        int samples() {
            return samples;
        }

        @Override
        public void close() {
            synchronized (SamplingBudget.this) {
                if (pending.remove(player, this)) {
                    outstanding -= samples;
                }
            }
        }
    }
}
