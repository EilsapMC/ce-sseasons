package dev.ceseasons.storage;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Versioned UTF-8 Properties persistence. One executor owns all disk commits.
 * Each accepted save copies its input, receives a monotonically increasing
 * revision, and is queued in that same order. A complete forced temporary file
 * replaces the target atomically; there is deliberately no non-atomic fallback.
 * The path must have only one owning SeasonStore instance at a time.
 */
public final class SeasonStore implements AutoCloseable {
    public record StoredSeason(long cycleTicks, boolean paused) {
    }

    private static final String FORMAT_VERSION = "1";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private final Object lifecycle = new Object();
    private final Path file;
    private final Consumer<Throwable> errors;
    private final long closeTimeoutNanos;
    private final AtomicMove atomicMove;
    private final ExecutorService executor;
    private volatile Thread worker;
    private volatile boolean abortWrites;
    private boolean closed;
    private long requestedRevision;
    // Before submission, guarded by lifecycle; afterward, used only by worker.
    private boolean initialized;
    private long baselineRevision;
    private Map<UUID, StoredSeason> initialState = Map.of();
    private IOException loadFailure;

    public SeasonStore(Path file, Consumer<Throwable> errors) {
        this(file, errors, CLOSE_TIMEOUT, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    // Package-private filesystem seam enables deterministic failure/race tests.
    SeasonStore(Path file, Consumer<Throwable> errors, Duration closeTimeout, AtomicMove atomicMove) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.errors = Objects.requireNonNull(errors, "errors");
        this.atomicMove = Objects.requireNonNull(atomicMove, "atomicMove");
        this.closeTimeoutNanos = Objects.requireNonNull(closeTimeout, "closeTimeout").toNanos();
        if (closeTimeoutNanos <= 0) {
            throw new IllegalArgumentException("closeTimeout must be positive");
        }
        if (this.file.getFileName() == null) {
            throw new IllegalArgumentException("file must name a file");
        }
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = Thread.ofPlatform().daemon(true).name("season-store").unstarted(task);
            worker = thread;
            return thread;
        });
    }

    /**
     * Reads the initial state before the first save. Only a missing file means
     * an empty state. Invalid/truncated/unsupported files throw and are retained.
     * A failed load latches this instance read-only, preventing accidental reset.
     */
    public Map<UUID, StoredSeason> load() throws IOException {
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("SeasonStore is closed");
            }
            if (requestedRevision != 0) {
                throw new IllegalStateException("load must precede the first save");
            }
            try {
                initialize();
                return initialState;
            } catch (IOException failure) {
                report(failure);
                throw failure;
            }
        }
    }

    /** Copies now and commits asynchronously. Concurrent callers are serialized. */
    public void save(Map<UUID, StoredSeason> seasons) {
        Map<UUID, StoredSeason> immutable = Map.copyOf(Objects.requireNonNull(seasons, "seasons"));
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("SeasonStore is closed");
            }
            long revision = Math.incrementExact(requestedRevision);
            executor.execute(() -> commit(immutable, revision));
            requestedRevision = revision;
        }
    }

    /**
     * Stops accepting saves and drains the final accepted submission for at most
     * five seconds. Timeout/interruption is reported, pending work cancelled.
     * As with JDK file IO, an already executing filesystem operation may not be
     * interruptible; the daemon writer cannot hold the JVM open.
     */
    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            executor.shutdown();
        }
        if (Thread.currentThread() == worker) {
            // An error callback may close the store. Never await our own thread.
            return;
        }
        try {
            if (!executor.awaitTermination(closeTimeoutNanos, TimeUnit.NANOSECONDS)) {
                abortWrites = true;
                executor.shutdownNow();
                report(new TimeoutException("SeasonStore did not finish its final save before close timeout"));
            }
        } catch (InterruptedException interrupted) {
            abortWrites = true;
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            report(interrupted);
        }
    }

    private void initialize() throws IOException {
        if (loadFailure != null) {
            throw new IOException("Refusing to overwrite a season file that failed to load: " + file, loadFailure);
        }
        if (initialized) {
            return;
        }
        try {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException missing) {
                initialState = Map.of();
                baselineRevision = 0;
                initialized = true;
                return;
            }
            Properties properties = new UniqueProperties();
            properties.load(new StringReader(text));
            if (!FORMAT_VERSION.equals(required(properties, "formatVersion"))) {
                throw new IOException("Unsupported season file format");
            }
            long revision = Long.parseLong(required(properties, "revision"));
            int count = Integer.parseInt(required(properties, "worldCount"));
            if (revision < 0 || count < 0 || properties.size() != 3L + 2L * count) {
                throw new IOException("Invalid season revision or world count");
            }
            Set<String> remaining = new HashSet<>(properties.stringPropertyNames());
            remaining.removeAll(Set.of("formatVersion", "revision", "worldCount"));
            Map<UUID, StoredSeason> loaded = new HashMap<>();
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("world.") || !key.endsWith(".cycleTicks")) {
                    continue;
                }
                int idEnd = key.length() - ".cycleTicks".length();
                if (idEnd <= 6) {
                    throw new IOException("Missing world UUID in season property: " + key);
                }
                String idText = key.substring(6, idEnd);
                UUID id = UUID.fromString(idText);
                if (!id.toString().equals(idText)) {
                    throw new IOException("Non-canonical world UUID: " + idText);
                }
                long ticks = Long.parseLong(required(properties, key));
                String pauseKey = "world." + idText + ".paused";
                String pauseText = required(properties, pauseKey);
                if (!pauseText.equals("true") && !pauseText.equals("false")) {
                    throw new IOException("Invalid paused value for world " + id);
                }
                loaded.put(id, new StoredSeason(ticks, Boolean.parseBoolean(pauseText)));
                remaining.remove(key);
                remaining.remove(pauseKey);
            }
            if (loaded.size() != count || !remaining.isEmpty()) {
                throw new IOException("Incomplete or unknown world entries in season file");
            }
            initialState = Map.copyOf(loaded);
            baselineRevision = revision;
            initialized = true;
        } catch (IOException failure) {
            loadFailure = failure;
            throw failure;
        } catch (IllegalArgumentException malformed) {
            loadFailure = new IOException("Malformed season file: " + file, malformed);
            throw loadFailure;
        }
    }

    private void commit(Map<UUID, StoredSeason> seasons, long revision) {
        Path temporary = null;
        try {
            if (abortWrites) {
                return;
            }
            initialize();
            long diskRevision = Math.addExact(baselineRevision, revision);
            Properties properties = new Properties();
            properties.setProperty("formatVersion", FORMAT_VERSION);
            properties.setProperty("revision", Long.toString(diskRevision));
            properties.setProperty("worldCount", Integer.toString(seasons.size()));
            seasons.forEach((id, state) -> {
                String prefix = "world." + id + ".";
                properties.setProperty(prefix + "cycleTicks", Long.toString(state.cycleTicks()));
                properties.setProperty(prefix + "paused", Boolean.toString(state.paused()));
            });
            StringWriter text = new StringWriter();
            properties.store(text, "Season state - format version " + FORMAT_VERSION);
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(text.toString());
            Path parent = file.getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".season-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            if (!abortWrites && !Thread.currentThread().isInterrupted()) {
                atomicMove.move(temporary, file);
            }
        } catch (Exception failure) {
            report(failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    report(cleanupFailure);
                }
            }
        }
    }

    private void report(Throwable failure) {
        try {
            errors.accept(failure);
        } catch (Throwable callbackFailure) {
            if (callbackFailure != failure) {
                failure.addSuppressed(callbackFailure);
            }
            System.getLogger(SeasonStore.class.getName()).log(System.Logger.Level.ERROR,
                    "Season persistence error (error callback also failed)", failure);
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IOException("Missing season property: " + key);
        }
        return value;
    }

    @FunctionalInterface
    interface AtomicMove {
        void move(Path source, Path target) throws IOException;
    }

    private static final class UniqueProperties extends Properties {
        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                throw new IllegalArgumentException("Duplicate season property: " + key);
            }
            return super.put(key, value);
        }
    }
}
