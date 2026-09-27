package dev.ceseasons.visual;

import dev.ceseasons.platform.v26_3.BiomePacketAdapter;
import dev.ceseasons.platform.v26_3.BiomeRegistryAdapter;
import dev.ceseasons.season.SeasonSnapshot;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.bukkit.plugin.network.listener.game.LevelChunkWithLightListener;
import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.world.chunk.PalettedContainer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * CE full chunks plus an owned pre-encoder biome-only handler. Bukkit world/entity
 * access is confined to enable and owner tasks, never to either packet callback.
 */
public final class SeasonVisualService implements AutoCloseable, Listener {
    private static final String HANDLER = "ceseasons_biomes_26_3";
    private static final Object REGISTRATION_LOCK = new Object();
    private static volatile SeasonVisualService active;
    private static boolean remapperRegistered;
    private final JavaPlugin plugin;
    private final Function<UUID, SeasonSnapshot> snapshots;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile java.util.Map<net.momirealms.craftengine.core.world.World, WorldIdentity> protocolWorlds = java.util.Map.of();
    private final Object worldLock = new Object();
    private record WorldIdentity(UUID uuid, String dimension, int sections) {}
    private final RefreshQueue queue = new RefreshQueue(4096);
    private final java.util.concurrent.Semaphore refreshPermits = new java.util.concurrent.Semaphore(128);
    private final AtomicLong epochs = new AtomicLong();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private volatile State state;
    private volatile boolean closed;
    private boolean started;
    private BiomePacketAdapter adapter;
    private ScheduledTask drainTask;
    private List<BiomeMappings.Definition> definitions;

    private record State(boolean enabled, BiomeMappings mappings, long epoch, int budget, int interval) {}
    private record Context(UUID world, long epoch, int sections) {}
    private record Appearance(long configuration, UUID world, long connection, int phase, int tropical) {}

    public SeasonVisualService(JavaPlugin plugin, Function<UUID, SeasonSnapshot> snapshots) {
        this.plugin = Objects.requireNonNull(plugin);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    /** Pure configuration validation. Registry and protocol probes run in start/reload, not here. */
    public static void validate(FileConfiguration config) {
        bounded(config, "visual.refresh-chunks-per-tick", 2, 1, 64);
        bounded(config, "visual.refresh-interval-ticks", 5, 1, 1200);
        for (String path : List.of("climate.blacklisted-biomes", "climate.tropical-biomes")) {
            for (String key : config.getStringList(path)) {
                if (!key.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                    throw new IllegalArgumentException("Invalid biome key in " + path + ": " + key);
            }
        }
    }

    private static int bounded(FileConfiguration config, String key, int fallback, int min, int max) {
        int value = config.getInt(key, fallback);
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return value;
    }

    public synchronized void start(FileConfiguration config) {
        if (closed) throw new IllegalStateException("Season visuals already closed");
        if (started) throw new IllegalStateException("Season visuals already started; use reload");
        validate(config);
        if (!Bukkit.getMinecraftVersion().equals("26.3"))
            throw new IllegalStateException("Season visuals require exact Minecraft 26.3, found " + Bukkit.getMinecraftVersion());
        try {
            definitions = BiomeMappings.read(getClass().getResourceAsStream("/season_datapack/biomes.index"));
            adapter = new BiomePacketAdapter();
            State next = prepare(config);
            for (World world : Bukkit.getWorlds()) cacheWorld(world);
            synchronized (REGISTRATION_LOCK) {
                if (active != null && active != this) throw new IllegalStateException("A season visual service is already active");
                if (!remapperRegistered) {
                    LevelChunkWithLightListener.addBiomeRemapper((player, section, biomes) -> {
                        SeasonVisualService service = active;
                        return service != null && service.remap(player, biomes);
                    });
                    remapperRegistered = true;
                }
                state = next;
                active = this;
            }
            started = true;
            Bukkit.getPluginManager().registerEvents(this, plugin);
            drainTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> drain(), 1L, 1L);
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                player.getScheduler().run(plugin, ignored -> attach(player), null);
            }
        } catch (ReflectiveOperationException | IOException | LinkageError error) {
            close();
            throw new IllegalStateException("CESeasons cannot initialize 26.3 server biome/packet support", error);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    private State prepare(FileConfiguration config) {
        Set<String> blacklist = Set.copyOf(config.getStringList("climate.blacklisted-biomes"));
        Set<String> tropical = Set.copyOf(config.getStringList("climate.tropical-biomes"));
        // A dry/wet codec cannot be turned into a temperate codec at runtime.
        // Reject incompatible reclassification rather than silently emit incorrect precipitation.
        if (config.contains("climate.tropical-biomes")) {
            for (var definition : definitions) {
                if (definition.seasonal() && !blacklist.contains(definition.base())
                        && tropical.contains(definition.base()) != definition.tropical()) {
                    throw new IllegalArgumentException("Tropical classification differs from bootstrap variants for "
                            + definition.base() + "; regenerate datapack and restart");
                }
            }
        }
        BiomeMappings mappings = BiomeRegistryAdapter.load(definitions, blacklist);
        return new State(config.getBoolean("visual.enabled", true), mappings, epochs.incrementAndGet(),
                bounded(config, "visual.refresh-chunks-per-tick", 2, 1, 64),
                bounded(config, "visual.refresh-interval-ticks", 5, 1, 1200));
    }

    public synchronized void reload(FileConfiguration config) {
        if (!started || closed) throw new IllegalStateException("Season visuals are not running");
        validate(config);
        State next = prepare(config); // validate completely before atomic publication
        State previous = state;
        if (previous.mappings().size() != next.mappings().size())
            throw new IllegalStateException("Biome registry size changed; a full server restart is required");
        // Clear before publication: owner threads observing next must not lose their new scan tickets.
        queue.clear();
        state = next;
    }

    private boolean remap(Player player, PalettedContainer<Integer> biomes) {
        State current = state;
        if (closed || current == null || !current.enabled()) return false;
        if (player.clientBiomeList().size() != current.mappings().size()) {
            IllegalStateException failure = new IllegalStateException("CE full-chunk client/server biome idList sizes differ");
            player.nettyChannel().close();
            fail(failure);
            return false;
        }
        // This is CE's protocol-side world, not platformPlayer()/Bukkit.getWorld().
        var identity = protocolWorlds.get(player.clientSideWorld());
        if (identity == null) return false;
        SeasonSnapshot snapshot = snapshot(identity.uuid());
        int phase = snapshot == null ? -1 : snapshot.subSeason().ordinal();
        int tropical = snapshot == null ? 0 : snapshot.tropicalSeason().ordinal();
        boolean changed = false;
        for (int i = 0; i < 64; i++) {
            int old = biomes.get(i);
            int replacement = current.mappings().remap(old, phase, tropical);
            if (old != replacement) { biomes.set(i, replacement); changed = true; }
        }
        return changed;
    }

    private SeasonSnapshot snapshot(UUID world) {
        SeasonSnapshot result = snapshots.apply(world);
        return result != null && world.equals(result.worldId()) ? result : null;
    }

    // CE BukkitWorld.uuid() defaults to bukkitWorld().getUID(); even that indirect
    // access is avoided on Netty by resolving clientSideWorld object identity here.
    private void cacheWorld(World world) {
        var protocol = net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(world);
        WorldIdentity identity = new WorldIdentity(world.getUID(), world.getKey().toString(),
                (world.getMaxHeight() - world.getMinHeight()) / 16);
        synchronized (worldLock) {
            if (closed) return;
            var copy = new java.util.IdentityHashMap<net.momirealms.craftengine.core.world.World, WorldIdentity>(protocolWorlds);
            copy.put(protocol, identity);
            protocolWorlds = java.util.Collections.unmodifiableMap(copy);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        if (!closed) cacheWorld(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID();
        synchronized (worldLock) {
            var copy = new java.util.IdentityHashMap<net.momirealms.craftengine.core.world.World, WorldIdentity>(protocolWorlds);
            copy.values().removeIf(value -> value.uuid().equals(id));
            protocolWorlds = java.util.Collections.unmodifiableMap(copy);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { attach(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) session.detach();
        queue.removePlayer(event.getPlayer().getUniqueId());
    }

    private void attach(org.bukkit.entity.Player player) {
        if (closed || sessions.containsKey(player.getUniqueId())) return;
        cacheWorld(player.getWorld());
        BukkitNetworkManager manager = BukkitNetworkManager.instance();
        Channel channel = manager.getChannel(player); // only on this player's owner
        if (channel == null) return; // no client exists for a fake player
        Session session = new Session(player, channel);
        if (sessions.putIfAbsent(player.getUniqueId(), session) != null) return;
        channel.eventLoop().execute(() -> {
            if (closed || session.retired) return;
            try {
                var user = manager.getUser(channel);
                if (!(user instanceof BukkitServerPlayer cePlayer))
                    throw new IllegalStateException("CraftEngine user is not ready on joined connection");
                session.user = cePlayer;
                String encoder = channel.pipeline().names().stream()
                        .filter(name -> adapter.isEncoder(channel.pipeline().get(name))).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Exact 26.3 PacketEncoder missing from player pipeline"));
                if (channel.pipeline().get(HANDLER) != null)
                    throw new IllegalStateException("Duplicate season biome channel handler");
                session.handler = new BiomeHandler(session);
                // Outbound travels tail -> head: this runs after bundle unpacking, before NMS encoding.
                channel.pipeline().addAfter(encoder, HANDLER, session.handler);
                session.updateContext();
            } catch (Throwable failure) { fail(failure); }
        });
        session.task = player.getScheduler().runAtFixedRate(plugin, ignored -> {
            try { session.poll(); } catch (RuntimeException failure) { if (!closed) fail(failure); }
        }, session::detach, 1L, 1L);
        if (session.task == null || closed || session.retired) session.detach();
    }

    private final class Session {
        final org.bukkit.entity.Player player;
        final UUID id;
        final Channel channel;
        volatile Context context;
        volatile boolean retired;
        BukkitServerPlayer user; // event-loop confined
        BiomeHandler handler; // event-loop confined
        volatile ScheduledTask task;
        long connectionEpoch; // event-loop confined
        int ticks; // remaining members player-owner confined
        Appearance appearance;
        Iterator<Long> scan;
        Long retryChunk;

        Session(org.bukkit.entity.Player player, Channel channel) {
            this.player = player; this.id = player.getUniqueId(); this.channel = channel;
        }

        void updateContext() {
            WorldIdentity identity = protocolWorlds.get(user.clientSideWorld());
            context = identity == null ? null : new Context(identity.uuid(), ++connectionEpoch, identity.sections());
        }

        void poll() {
            if (closed || retired) return;
            State current = state;
            if (current == null) return;
            if (++ticks < current.interval()) return;
            ticks = 0;
            Context captured = context;
            if (captured == null || !player.isOnline() || !player.getWorld().getUID().equals(captured.world())) return;
            SeasonSnapshot snapshot = snapshot(captured.world());
            int phase = current.enabled() && snapshot != null ? snapshot.subSeason().ordinal() : -1;
            int tropical = current.enabled() && snapshot != null ? snapshot.tropicalSeason().ordinal() : -1;
            Appearance now = new Appearance(current.epoch(), captured.world(), captured.epoch(), phase, tropical);
            if (!now.equals(appearance)) {
                appearance = now;
                scan = player.getSentChunkKeys().iterator();
                retryChunk = null;
            }
            if (scan == null) return;
            for (int i = 0; i < 64 && (retryChunk != null || scan.hasNext()); i++) {
                long key = retryChunk != null ? retryChunk : scan.next();
                if (!player.isChunkSent(key)) { retryChunk = null; continue; }
                long revision = snapshot == null ? -1 : snapshot.revision();
                if (!queue.offer(new RefreshQueue.Ticket(id, captured.world(), key, captured.epoch(),
                        current.epoch(), revision, phase, tropical))) { retryChunk = key; return; }
                retryChunk = null;
            }
            if (retryChunk == null && !scan.hasNext()) scan = null;
        }

        void retire() {
            retired = true; context = null;
            if (sessions.remove(id, this)) queue.removePlayer(id);
        }

        void detach() {
            retire();
            if (task != null) task.cancel();
            Runnable remove = () -> {
                if (handler != null && channel.pipeline().get(HANDLER) == handler) channel.pipeline().remove(HANDLER);
            };
            try {
                if (channel.eventLoop().inEventLoop()) remove.run();
                else channel.eventLoop().execute(remove);
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                // A terminated channel event loop cannot send more packets; never touch others' handlers.
                if (channel.isOpen()) plugin.getLogger().log(Level.WARNING, "Could not detach season handler from live channel", stopped);
            }
        }
    }

    private final class BiomeHandler extends ChannelOutboundHandlerAdapter {
        private final Session session;
        BiomeHandler(Session session) { this.session = session; }

        @Override
        public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) throws Exception {
            if (closed || session.retired) { ctx.write(packet, promise); return; }
            if (adapter.isOutboundConfigurationTask(packet)) {
                session.context = null;
                ++session.connectionEpoch;
                ctx.write(packet, promise);
                // 26.3 reinserts its bundle unpacker immediately after the new encoder.
                // Move only our own handler so unpacked biome packets cannot bypass it.
                try {
                    String encoder = ctx.pipeline().names().stream()
                            .filter(name -> adapter.isEncoder(ctx.pipeline().get(name))).findFirst()
                            .orElseThrow(() -> new IllegalStateException("26.3 PacketEncoder missing after reconfiguration"));
                    if (ctx.pipeline().get(HANDLER) != this)
                        throw new IllegalStateException("Season handler ownership changed during reconfiguration");
                    ctx.pipeline().remove(this);
                    session.handler = new BiomeHandler(session);
                    ctx.pipeline().addAfter(encoder, HANDLER, session.handler);
                } catch (Throwable failure) { ctx.close(); fail(failure); }
                return;
            }
            if (adapter.isDimensionBarrier(packet)) {
                session.context = null;
                ++session.connectionEpoch;
                ctx.write(packet, promise); // CE's byte-buffer login/respawn callback updates clientSideWorld here
                if (!adapter.isConfigurationBarrier(packet)) {
                    try {
                        WorldIdentity identity = protocolWorlds.get(session.user.clientSideWorld());
                        if (identity == null || !adapter.dimension(packet).equals(identity.dimension()))
                            throw new IllegalStateException("CE protocol dimension did not follow 26.3 login/respawn barrier");
                        session.updateContext();
                    } catch (Throwable failure) { ctx.close(); fail(failure); }
                }
                return;
            }
            State current = state;
            if (current == null || !current.enabled() || !adapter.isBiomePacket(packet)) { ctx.write(packet, promise); return; }
            try {
                WorldIdentity identity = protocolWorlds.get(session.user.clientSideWorld());
                Context context = session.context;
                if (context == null || identity == null || !context.world().equals(identity.uuid())) {
                    throw new IllegalStateException("Biome-only packet crossed an unresolved client dimension barrier");
                }
                SeasonSnapshot snapshot = snapshot(context.world());
                int phase = snapshot == null ? -1 : snapshot.subSeason().ordinal();
                int tropical = snapshot == null ? 0 : snapshot.tropicalSeason().ordinal();
                Object replacement = adapter.copyAndRemap(packet, session.user, context.sections(), current.mappings().size(),
                        raw -> current.mappings().remap(raw, phase, tropical));
                ctx.write(replacement, promise);
            } catch (Throwable failure) {
                promise.tryFailure(failure);
                ctx.close(); // never emit a malformed or wrong-dimension palette
                fail(failure);
            }
        }
    }

    private void drain() {
        State current = state;
        if (closed || current == null) return;
        for (int i = 0; i < current.budget(); i++) {
            if (!refreshPermits.tryAcquire()) return;
            AtomicBoolean released = new AtomicBoolean();
            Runnable release = () -> { if (released.compareAndSet(false, true)) refreshPermits.release(); };
            RefreshQueue.Ticket ticket = queue.poll();
            if (ticket == null) { release.run(); return; }
            Session session = sessions.get(ticket.player());
            if (session == null || !valid(session, ticket)) { release.run(); continue; }
            try {
                ScheduledTask scheduled = session.player.getScheduler().run(plugin, ignored -> {
                    boolean handedOff = false;
                    try {
                        if (!valid(session, ticket) || !session.player.isOnline()
                                || !session.player.getWorld().getUID().equals(ticket.world())
                                || !session.player.isChunkSent(ticket.chunk())) return;
                        World world = session.player.getWorld();
                        int x = (int) ticket.chunk();
                        int z = (int) (ticket.chunk() >>> 32);
                        Bukkit.getRegionScheduler().run(plugin, world, x, z, task -> {
                            try {
                                if (!valid(session, ticket) || !world.isChunkLoaded(x, z)) return;
                                // Owner-only tracking query; never getChunkAt or force-load.
                                if (world.getPlayersSeeingChunk(x, z).stream()
                                        .noneMatch(p -> p.getUniqueId().equals(ticket.player()))) return;
                                world.refreshChunk(x, z);
                            } finally { release.run(); }
                        });
                        handedOff = true;
                    } finally { if (!handedOff) release.run(); }
                }, release);
                if (scheduled == null) release.run();
            } catch (RuntimeException failure) {
                release.run();
                if (!closed) fail(failure);
            }
        }
    }

    private boolean valid(Session session, RefreshQueue.Ticket ticket) {
        State current = state;
        Context context = session.context;
        if (closed || session.retired || context == null || current == null) return false;
        SeasonSnapshot snapshot = snapshot(ticket.world());
        int phase = current.enabled() && snapshot != null ? snapshot.subSeason().ordinal() : -1;
        int tropical = current.enabled() && snapshot != null ? snapshot.tropicalSeason().ordinal() : -1;
        // Clock revisions advance every sample. Reject phase changes, not harmless newer ticks.
        return ticket.matches(context.world(), context.epoch(), current.epoch(),
                snapshot == null ? -1 : snapshot.revision(), phase, tropical);
    }

    private void fail(Throwable failure) {
        if (!failureReported.compareAndSet(false, true)) return;
        plugin.getLogger().log(Level.SEVERE, "Season biome protocol support failed; disabling rather than leaving partial coverage", failure);
        close();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.getPluginManager().disablePlugin(plugin));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        synchronized (REGISTRATION_LOCK) { if (active == this) active = null; }
        // Deliberately do not call clearBiomeRemappers: other plugins own their callbacks.
        if (drainTask != null) drainTask.cancel();
        HandlerList.unregisterAll(this);
        for (Session session : sessions.values()) session.detach();
        sessions.clear(); queue.clear();
        state = null;
    }
}
