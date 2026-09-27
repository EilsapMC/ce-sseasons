package dev.ceseasons.calendar;

import dev.ceseasons.agriculture.AgricultureSettings;
import dev.ceseasons.season.SeasonSnapshot;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

public final class CalendarService implements Listener, AutoCloseable {
    public static final String ITEM_ID = "ce_seasons:calendar";
    private final JavaPlugin plugin;
    private final Function<UUID, SeasonSnapshot> snapshots;
    private final Supplier<AgricultureSettings> settings;
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private volatile boolean running;

    public CalendarService(JavaPlugin plugin, Function<UUID, SeasonSnapshot> snapshots, Supplier<AgricultureSettings> settings) {
        this.plugin = plugin; this.snapshots = snapshots; this.settings = settings;
    }
    public void start() {
        if (running) return;
        running = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) schedule(player);
    }
    @EventHandler public void join(PlayerJoinEvent event) { schedule(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        ScheduledTask task = tasks.remove(event.getPlayer().getUniqueId());
        if (task != null) task.cancel();
    }
    private void schedule(Player player) {
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, t -> {
            if (!running) { t.cancel(); return; }
            refresh(player);
        }, () -> tasks.remove(player.getUniqueId()), 1, 20);
        if (task != null) {
            ScheduledTask previous = tasks.put(player.getUniqueId(), task);
            if (previous != null) previous.cancel();
        }
    }
    public ItemBehavior behavior() {
        return new ItemBehavior() {
            @Override public InteractionResult use(net.momirealms.craftengine.core.world.World world, net.momirealms.craftengine.core.entity.player.Player player, InteractionHand hand) {
                return show(player);
            }
            @Override public InteractionResult useOnBlock(UseOnContext context) { return show(context.getPlayer()); }
        };
    }
    private InteractionResult show(net.momirealms.craftengine.core.entity.player.Player cePlayer) {
        if (!running || cePlayer == null) return InteractionResult.PASS;
        Player player = (Player) cePlayer.platformPlayer();
        player.getScheduler().execute(plugin, () -> {
            if (!running) return;
            refresh(player);
            player.sendMessage(description(player));
        }, null, 1);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }
    private boolean tropical(Player player) {
        return settings.get().tropicalBiomes().contains(player.getLocation().getBlock().getBiome().getKey().toString());
    }
    private SeasonSnapshot snapshot(Player player) {
        var config = settings.get();
        World world = player.getWorld();
        if (config.excludedWorlds().contains(world.getName()) || config.excludedWorlds().contains(world.getUID().toString())
                || config.blacklistedBiomes().contains(player.getLocation().getBlock().getBiome().getKey().toString())) return null;
        return snapshots.apply(world.getUID());
    }
    private Component description(Player player) {
        SeasonSnapshot snapshot = snapshot(player);
        if (snapshot == null) return Component.text("季节日历 · 未知（此世界或生物群系未启用）", NamedTextColor.GRAY);
        boolean tropical = tropical(player);
        String stage = tropical ? snapshot.tropicalSeason().name() : snapshot.subSeason().name();
        long day = tropical ? snapshot.dayOfTropicalSeason() : snapshot.dayOfSubSeason();
        return Component.text("季节日历 · " + stage + " · 第 " + day + " 天" + (snapshot.paused() ? " · 已暂停" : ""), NamedTextColor.GOLD);
    }
    private void refresh(Player player) {
        SeasonSnapshot snapshot = snapshot(player);
        boolean tropical = snapshot != null && tropical(player);
        String model = snapshot == null ? "unknown" : "stage_" + (tropical ? 12 + snapshot.tropicalSeason().ordinal() : snapshot.subSeason().ordinal());
        Component description = description(player);
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            var id = CraftEngineItems.getCustomItemId(item);
            if (id == null || !ITEM_ID.equals(id.asString())) continue;
            ItemMeta meta = item.getItemMeta();
            NamespacedKey key = new NamespacedKey("ce_seasons", "calendar/" + model);
            List<Component> lore = List.of(description, Component.text("右键查看当地季节 · 18 种季相", NamedTextColor.GRAY));
            if (!key.equals(meta.getItemModel()) || !lore.equals(meta.lore())) {
                meta.setItemModel(key); meta.lore(lore); item.setItemMeta(meta);
            }
        }
    }
    @Override public void close() {
        running = false; HandlerList.unregisterAll(this);
        tasks.values().forEach(ScheduledTask::cancel); tasks.clear();
    }
}
