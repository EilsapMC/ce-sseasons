package dev.ceseasons.visual;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RefreshQueueTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static RefreshQueue.Ticket ticket(long chunk, long epoch) {
        return new RefreshQueue.Ticket(PLAYER, WORLD, chunk, epoch, 1, 1, 0, 0);
    }

    @Test void boundedAndDeduplicatedWithoutDroppingNewerEpoch() {
        RefreshQueue queue = new RefreshQueue(2);
        assertTrue(queue.offer(ticket(1, 1)));
        assertTrue(queue.offer(ticket(2, 1)));
        assertFalse(queue.offer(ticket(3, 1)));
        assertTrue(queue.offer(ticket(1, 2)));
        assertEquals(2, queue.size());
        assertEquals(ticket(1, 2), queue.poll());
        assertEquals(ticket(2, 1), queue.poll());
        assertNull(queue.poll());
    }

    @Test void quitAndCloseDiscardPendingWork() {
        RefreshQueue queue = new RefreshQueue(3);
        queue.offer(ticket(1, 1));
        var other = new RefreshQueue.Ticket(UUID.randomUUID(), WORLD, 1, 1, 1, 1, 0, 0);
        queue.offer(other);
        queue.removePlayer(PLAYER);
        assertEquals(other, queue.poll());
        queue.offer(ticket(2, 2));
        queue.clear();
        assertEquals(0, queue.size());
        assertNull(queue.poll());
    }

    @Test void dimensionsAndEpochsRejectStaleWorkButNewClockTicksDoNotStarve() {
        var ticket = new RefreshQueue.Ticket(PLAYER, WORLD, 1, 4, 9, 100, 2, 5);
        assertTrue(ticket.matches(WORLD, 4, 9, 101, 2, 5));
        assertFalse(ticket.matches(UUID.randomUUID(), 4, 9, 101, 2, 5));
        assertFalse(ticket.matches(WORLD, 5, 9, 101, 2, 5));
        assertFalse(ticket.matches(WORLD, 4, 10, 101, 2, 5));
        assertFalse(ticket.matches(WORLD, 4, 9, 99, 2, 5));
        assertFalse(ticket.matches(WORLD, 4, 9, 101, 3, 5));
        assertFalse(ticket.matches(WORLD, 4, 9, 101, 2, 4));
    }

    @Test void configurationReloadReplacesPendingTickets() {
        RefreshQueue queue = new RefreshQueue(1);
        var previous = new RefreshQueue.Ticket(PLAYER, WORLD, 1, 4, 9, 100, 2, 5);
        var current = new RefreshQueue.Ticket(PLAYER, WORLD, 1, 4, 10, 100, 2, 5);
        assertTrue(queue.offer(previous));
        assertTrue(queue.offer(current));
        assertEquals(current, queue.poll());
        assertFalse(previous.matches(WORLD, 4, 10, 100, 2, 5));
        assertTrue(current.matches(WORLD, 4, 10, 100, 2, 5));
        assertNull(queue.poll());
    }

    @Test void rejectsUnboundedQueue() {
        assertThrows(IllegalArgumentException.class, () -> new RefreshQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new RefreshQueue(-1));
    }
}
