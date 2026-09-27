package dev.ceseasons.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SeasonStoreTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String HEADER = "formatVersion=1\nrevision=7\nworldCount=1\n";
    private static final String WORLD_PREFIX = "world." + WORLD + ".";

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("seasons.properties");
    }

    private static Map<UUID, SeasonStore.StoredSeason> state(long tick) {
        return Map.of(WORLD, new SeasonStore.StoredSeason(tick, false));
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test synchronization timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("test synchronization interrupted", interrupted);
        }
    }

    private static Properties properties(Path path) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(path, StandardCharsets.UTF_8)));
        return properties;
    }

    private static long revision(Path path) throws IOException {
        return Long.parseLong(properties(path).getProperty("revision"));
    }

    private Map<UUID, SeasonStore.StoredSeason> readSaved() throws IOException {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore reader = new SeasonStore(file(), errors::add)) {
            Map<UUID, SeasonStore.StoredSeason> result = reader.load();
            assertTrue(errors.isEmpty(), errors::toString);
            return result;
        }
    }

    private void seed(long tick) {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            store.save(state(tick));
        }
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void missingFileIsTheOnlyImplicitEmptyState() throws IOException {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            assertEquals(Map.of(), store.load());
        }
        assertTrue(errors.isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    void roundTripPreservesSignedLongTicksPausedFlagsAndUuidKeys() throws IOException {
        Map<UUID, SeasonStore.StoredSeason> expected = Map.of(
                WORLD, new SeasonStore.StoredSeason(Long.MAX_VALUE, true),
                OTHER, new SeasonStore.StoredSeason(Long.MIN_VALUE, false));
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            store.save(expected);
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(expected, readSaved());
        assertEquals("1", properties(file()).getProperty("formatVersion"));
        assertEquals(1, revision(file()));
        assertEquals("2", properties(file()).getProperty("worldCount"));
    }

    @Test
    void loadedMapIsImmutable() throws IOException {
        seed(19);
        assertThrows(UnsupportedOperationException.class, () -> readSaved().clear());
    }

    @Test
    void closeDrainsEveryAcceptedSaveInFifoRevisionOrder() throws IOException {
        List<Long> revisions = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add, TEST_TIMEOUT, (source, target) -> {
            revisions.add(revision(source));
            atomicMove(source, target);
        })) {
            for (int index = 1; index <= 40; index++) {
                store.save(state(index));
            }
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(40, revisions.size());
        for (int index = 0; index < revisions.size(); index++) {
            assertEquals(index + 1L, revisions.get(index).longValue());
        }
        assertEquals(state(40), readSaved());
        assertEquals(40, revision(file()));
    }

    @Test
    void saveCapturesCallerMapBeforeAsyncExecution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicInteger moves = new AtomicInteger();
        SeasonStore store = new SeasonStore(file(), errors::add, TEST_TIMEOUT, (source, target) -> {
            if (moves.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
            }
            atomicMove(source, target);
        });
        try {
            store.save(state(1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Map<UUID, SeasonStore.StoredSeason> mutable = new HashMap<>(state(2));
            store.save(mutable);
            mutable.put(WORLD, new SeasonStore.StoredSeason(999, true));
            mutable.put(OTHER, new SeasonStore.StoredSeason(888, true));
        } finally {
            release.countDown();
            store.close();
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(state(2), readSaved());
    }

    @Test
    void concurrentCallersHaveSerialCommitsAndFinalSubmissionWins() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Long> revisions = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        try (SeasonStore store = new SeasonStore(file(), errors::add, TEST_TIMEOUT, (source, target) -> {
            int writers = active.incrementAndGet();
            maximum.accumulateAndGet(writers, Math::max);
            try {
                revisions.add(revision(source));
                atomicMove(source, target);
            } finally {
                active.decrementAndGet();
            }
        })) {
            try (var callers = Executors.newFixedThreadPool(4)) {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int index = 0; index < 32; index++) {
                    final int tick = index;
                    futures.add(callers.submit(() -> store.save(state(tick))));
                }
                for (var future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            }
            store.save(state(999));
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(1, maximum.get());
        assertEquals(33, revisions.size());
        for (int index = 0; index < revisions.size(); index++) {
            assertEquals(index + 1L, revisions.get(index).longValue());
        }
        assertEquals(state(999), readSaved());
    }

    @Test
    void revisionContinuesAcrossStoreRestarts() throws IOException {
        seed(10);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            assertEquals(state(10), store.load());
            store.save(state(20));
            store.save(state(30));
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(3, revision(file()));
        assertEquals(state(30), readSaved());
    }

    @Test
    void saveWithoutLoadStillValidatesExistingFileAndContinuesRevision() throws IOException {
        seed(10);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            store.save(state(20));
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(2, revision(file()));
        assertEquals(state(20), readSaved());
    }

    static Stream<String> corruptFiles() {
        return Stream.of(
                "",
                "# empty/truncated file\n",
                "formatVersion=99\nrevision=0\nworldCount=0\n",
                "formatVersion=1\nrevision=-1\nworldCount=0\n",
                "formatVersion=1\nrevision=abc\nworldCount=0\n",
                "formatVersion=1\nrevision=0\nworldCount=-1\n",
                "formatVersion=1\nrevision=0\nworldCount=0\nunknown=1\n",
                HEADER + WORLD_PREFIX + "cycleTicks=20\n",
                HEADER + WORLD_PREFIX + "cycleTicks=20\n" + WORLD_PREFIX + "paused=perhaps\n",
                HEADER + WORLD_PREFIX + "cycleTicks=9223372036854775808\n" + WORLD_PREFIX + "paused=false\n",
                HEADER + "world.invalid.cycleTicks=0\nworld.invalid.paused=false\n",
                HEADER + "world.cycleTicks=0\nworld.paused=false\n",
                HEADER + "world..cycleTicks=0\nworld..paused=false\n",
                HEADER + "world.0-0-0-0-1.cycleTicks=0\nworld.0-0-0-0-1.paused=false\n",
                HEADER + WORLD_PREFIX + "cycleTicks=20\n" + WORLD_PREFIX + "paused=false\nformatVersion=1\n",
                "formatVersion=1\nrevision=0\nworldCount=2\n" + WORLD_PREFIX + "cycleTicks=0\n" + WORLD_PREFIX + "paused=false\n",
                "formatVersion=1\nrevision=0\nworldCount=0\ninvalid=" + (char) 92 + "uZZZZ\n"
        );
    }

    @ParameterizedTest
    @MethodSource("corruptFiles")
    void corruptionIsReportedRetainedAndCannotBeOverwritten(String content) throws IOException {
        Files.writeString(file(), content, StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(file());
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            assertThrows(IOException.class, store::load);
            store.save(state(777));
        }
        assertTrue(errors.size() >= 2, errors::toString);
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test
    void corruptionCannotBeOverwrittenWhenLoadWasNeverCalled() throws IOException {
        Files.writeString(file(), "broken");
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            store.save(state(12));
        }
        assertFalse(errors.isEmpty());
        assertEquals("broken", Files.readString(file()));
    }

    @Test
    void failedLoadRemainsLatchedEvenIfFileIsLaterRemoved() throws IOException {
        Files.writeString(file(), "broken");
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            assertThrows(IOException.class, store::load);
            Files.delete(file());
            assertThrows(IOException.class, store::load);
            store.save(state(12));
        }
        assertFalse(Files.exists(file()));
        assertTrue(errors.size() >= 3);
    }

    @Test
    void unsupportedAtomicMovePreservesOriginalAndRemovesTemporary() throws IOException {
        seed(17);
        byte[] original = Files.readAllBytes(file());
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add, TEST_TIMEOUT, (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test filesystem");
        })) {
            store.save(state(99));
        }
        assertEquals(1, errors.size());
        assertInstanceOf(AtomicMoveNotSupportedException.class, errors.getFirst());
        assertArrayEquals(original, Files.readAllBytes(file()));
        try (Stream<Path> paths = Files.list(directory)) {
            assertEquals(List.of(file()), paths.toList());
        }
    }

    @Test
    void moveFailureDoesNotKillWriterAndNextSaveCanSucceed() throws IOException {
        seed(17);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicInteger moves = new AtomicInteger();
        try (SeasonStore store = new SeasonStore(file(), errors::add, TEST_TIMEOUT, (source, target) -> {
            if (moves.incrementAndGet() == 1) {
                throw new IOException("simulated move failure");
            }
            atomicMove(source, target);
        })) {
            store.save(state(30));
            store.save(state(40));
        }
        assertEquals(1, errors.size());
        assertEquals(state(40), readSaved());
        assertEquals(3, revision(file()));
    }

    @Test
    void throwingErrorCallbackDoesNotKillWriter() throws IOException {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger moves = new AtomicInteger();
        try (SeasonStore store = new SeasonStore(file(), failure -> {
            callbacks.incrementAndGet();
            throw new IllegalStateException("callback failure");
        }, TEST_TIMEOUT, (source, target) -> {
            if (moves.incrementAndGet() == 1) {
                throw new IOException("first move fails");
            }
            atomicMove(source, target);
        })) {
            store.save(state(1));
            store.save(state(2));
        }
        assertEquals(1, callbacks.get());
        assertEquals(state(2), readSaved());
    }

    @Test
    void oldTargetRemainsCompleteWhileNewTemporaryWaitsForMove() throws Exception {
        seed(17);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Path> temporary = new AtomicReference<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        SeasonStore store = new SeasonStore(file(), errors::add, TEST_TIMEOUT, (source, target) -> {
            temporary.set(source);
            ready.countDown();
            await(release);
            atomicMove(source, target);
        });
        try {
            store.save(state(88));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals("17", properties(file()).getProperty(WORLD_PREFIX + "cycleTicks"));
            assertEquals("88", properties(temporary.get()).getProperty(WORLD_PREFIX + "cycleTicks"));
            assertEquals(file().getParent(), temporary.get().getParent());
        } finally {
            release.countDown();
            store.close();
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(state(88), readSaved());
        assertFalse(Files.exists(temporary.get()));
    }

    @Test
    void boundedCloseInterruptsBlockedSaveReportsTimeoutAndPreservesOldTarget() throws Exception {
        seed(17);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        SeasonStore store = new SeasonStore(file(), errors::add, Duration.ofMillis(50), (source, target) -> {
            entered.countDown();
            try {
                await(release);
                atomicMove(source, target);
            } finally {
                finished.countDown();
            }
        });
        try {
            store.save(state(100));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), store::close);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertTrue(errors.stream().anyMatch(TimeoutException.class::isInstance), errors::toString);
            assertEquals(state(17), readSaved());
        } finally {
            release.countDown();
            store.close();
        }
    }

    @Test
    void interruptedCloseRestoresInterruptStatus() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        SeasonStore store = new SeasonStore(file(), errors::add, TEST_TIMEOUT, (source, target) -> {
            entered.countDown();
            await(release);
            atomicMove(source, target);
        });
        try {
            store.save(state(100));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            store.close();
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(errors.stream().anyMatch(InterruptedException.class::isInstance));
        } finally {
            Thread.interrupted();
            release.countDown();
            store.close();
        }
    }

    @Test
    void callbackCanCloseStoreWithoutAwaitingItsOwnWorker() throws Exception {
        AtomicReference<SeasonStore> reference = new AtomicReference<>();
        CountDownLatch callbackFinished = new CountDownLatch(1);
        SeasonStore store = new SeasonStore(file(), failure -> {
            reference.get().close();
            callbackFinished.countDown();
        }, TEST_TIMEOUT, (source, target) -> {
            throw new IOException("test callback close");
        });
        reference.set(store);
        try {
            store.save(state(1));
            assertTrue(callbackFinished.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> store.save(state(2)));
        } finally {
            store.close();
        }
    }

    @Test
    void revisionOverflowReportsFailureWithoutTouchingExistingTarget() throws IOException {
        String content = "formatVersion=1\nrevision=9223372036854775807\nworldCount=0\n";
        Files.writeString(file(), content);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            assertEquals(Map.of(), store.load());
            store.save(state(99));
        }
        assertEquals(1, errors.size());
        assertInstanceOf(ArithmeticException.class, errors.getFirst());
        assertEquals(content, Files.readString(file()));
    }

    @Test
    void existingDirectoryIsAnErrorNotAnEmptySave() throws IOException {
        Files.createDirectory(file());
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            assertThrows(IOException.class, store::load);
            store.save(state(12));
        }
        assertTrue(Files.isDirectory(file()));
        assertFalse(errors.isEmpty());
    }

    @Test
    void createsMissingParentDirectoriesAndSupportsShortFileNames() throws IOException {
        Path nested = directory.resolve("nested/deeper/a");
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(nested, errors::add)) {
            assertEquals(Map.of(), store.load());
            store.save(state(1));
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertTrue(Files.isRegularFile(nested));
    }

    @Test
    void closeIsIdempotentAndRejectsNewSavesAndLoads() {
        SeasonStore store = new SeasonStore(file(), failure -> fail(failure));
        store.close();
        store.close();
        assertThrows(IllegalStateException.class, () -> store.save(state(1)));
        assertThrows(IllegalStateException.class, store::load);
    }

    @Test
    void loadAfterFirstSubmissionIsRejected() {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            store.save(state(1));
            assertThrows(IllegalStateException.class, store::load);
        }
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void concurrentCloseNeverDropsAnAcceptedSave() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Long> accepted = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        SeasonStore store = new SeasonStore(file(), errors::add);
        store.save(state(0));
        accepted.add(0L);
        try (var callers = Executors.newFixedThreadPool(5)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (long tick = 1; tick <= 16; tick++) {
                final long value = tick;
                futures.add(callers.submit(() -> {
                    start.await();
                    try {
                        store.save(state(value));
                        accepted.add(value);
                    } catch (IllegalStateException closed) {
                        // Rejected submissions must not reach the on-disk state.
                    }
                    return null;
                }));
                if (tick == 3) {
                    futures.add(callers.submit(() -> {
                        start.await();
                        store.close();
                        return null;
                    }));
                }
            }
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            store.close();
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(accepted.size(), revision(file()));
        assertTrue(accepted.contains(readSaved().get(WORLD).cycleTicks()));
    }

    @Test
    void explicitlySavedEmptyStateIsValidAndVersioned() throws IOException {
        seed(10);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            store.save(Map.of());
        }
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(Map.of(), readSaved());
        assertEquals(2, revision(file()));
        assertEquals("0", properties(file()).getProperty("worldCount"));
    }

    @Test
    void invalidUtf8IsReportedAndRetained() throws IOException {
        byte[] original = {(byte) 0xc3, (byte) 0x28};
        Files.write(file(), original);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (SeasonStore store = new SeasonStore(file(), errors::add)) {
            assertThrows(IOException.class, store::load);
            store.save(state(11));
        }
        assertTrue(errors.size() >= 2);
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test
    void nullMapKeysAndValuesAreRejectedSynchronously() {
        try (SeasonStore store = new SeasonStore(file(), failure -> fail(failure))) {
            assertThrows(NullPointerException.class, () -> store.save(null));
            Map<UUID, SeasonStore.StoredSeason> badKey = new HashMap<>();
            badKey.put(null, new SeasonStore.StoredSeason(0, false));
            assertThrows(NullPointerException.class, () -> store.save(badKey));
            Map<UUID, SeasonStore.StoredSeason> badValue = new HashMap<>();
            badValue.put(WORLD, null);
            assertThrows(NullPointerException.class, () -> store.save(badValue));
        }
        assertFalse(Files.exists(file()));
    }
}
