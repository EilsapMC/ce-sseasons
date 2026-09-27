package dev.ceseasons.command;

import dev.ceseasons.SeasonsPlugin;
import dev.ceseasons.season.SeasonSnapshot;
import dev.ceseasons.season.SubSeason;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SeasonsCommand implements BasicCommand {
    private final SeasonsPlugin plugin;

    public SeasonsCommand(SeasonsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("ceseasons.query") || sender.hasPermission("ceseasons.admin");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String action = args.length == 0 ? "get" : args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("get") && !sender.hasPermission("ceseasons.admin")) {
            plugin.reply(sender, "你没有 ceseasons.admin 权限。");
            return;
        }
        if (action.equals("reload")) {
            plugin.reloadSettings(sender);
            return;
        }
        if (action.equals("give")) {
            give(sender, args);
            return;
        }
        if (!List.of("get", "set", "pause", "resume").contains(action)) {
            usage(sender);
            return;
        }
        SubSeason selected = null;
        if (action.equals("set")) {
            if (args.length < 2) { usage(sender); return; }
            try {
                selected = SubSeason.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                plugin.reply(sender, "未知子季节。可用：" + Arrays.toString(SubSeason.values()));
                return;
            }
        }
        SubSeason target = selected;
        int worldIndex = action.equals("set") ? 2 : 1;
        String worldName = args.length > worldIndex ? args[worldIndex] : null;
        // Command sources provide a captured location even for execute-as dispatch.
        UUID sourceWorld = source.getLocation().getWorld() == null ? null
                : source.getLocation().getWorld().getUID();
        plugin.runGlobal(() -> {
            World world = worldName == null ? (sourceWorld == null ? null : Bukkit.getWorld(sourceWorld))
                    : worldByNameOrId(worldName);
            if (world == null) {
                plugin.reply(sender, "请指定一个已加载世界：/ceseasons " + action
                        + (target == null ? "" : " " + target.name().toLowerCase(Locale.ROOT)) + " <world>");
                return;
            }
            UUID id = world.getUID();
            if (plugin.snapshot(id) == null) {
                plugin.reply(sender, "该世界未启用季节，请检查 worlds.enabled。");
                return;
            }
            switch (action) {
                case "set" -> plugin.seasons().setSeason(id, target);
                case "pause" -> plugin.seasons().setPaused(id, true);
                case "resume" -> plugin.seasons().setPaused(id, false);
                default -> { }
            }
            if (!action.equals("get")) plugin.saveState();
            SeasonSnapshot snapshot = plugin.snapshot(id);
            plugin.reply(sender, "[季节] " + world.getName() + " · " + snapshot.subSeason()
                    + " 第" + snapshot.dayOfSubSeason() + "天 · 热带 " + snapshot.tropicalSeason()
                    + " 第" + snapshot.dayOfTropicalSeason() + "天" + (snapshot.paused() ? "（已暂停）" : ""));
        });
    }

    private void give(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.reply(sender, "give 需要玩家执行；内容包物品也可用 CE 自身命令获取。");
            return;
        }
        String item = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "calendar";
        if (!List.of("calendar", "season_sensor").contains(item)) { usage(sender); return; }
        player.getScheduler().run(plugin, task -> {
            if (plugin.isStopping()) return;
            var definition = CraftEngineItems.byId(Key.of("ce_seasons:" + item));
            if (definition == null) {
                player.sendMessage("未加载 CE 季节内容包，请将 ZIP 安装至 CraftEngine/resources 后重启。");
                return;
            }
            if (player.getInventory().firstEmpty() == -1) {
                player.sendMessage("背包没有空位。");
                return;
            }
            player.getInventory().addItem(definition.buildBukkitItem(player));
            player.sendMessage("已发放 " + item + "。");
        }, null);
    }

    private static World worldByNameOrId(String value) {
        try {
            return Bukkit.getWorld(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Bukkit.getWorld(value);
        }
    }

    private void usage(CommandSender sender) {
        plugin.reply(sender, "/ceseasons get [world] | set <subseason> [world] | pause/resume [world]");
        plugin.reply(sender, "/ceseasons reload | give <calendar|season_sensor>");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        List<String> choices;
        if (args.length <= 1) {
            choices = source.getSender().hasPermission("ceseasons.admin")
                    ? List.of("get", "set", "pause", "resume", "reload", "give") : List.of("get");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            choices = Arrays.stream(SubSeason.values()).map(s -> s.name().toLowerCase(Locale.ROOT)).toList();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            choices = List.of("calendar", "season_sensor");
        } else {
            // No live world access from an asynchronous suggestion callback.
            choices = plugin.seasons() == null ? List.of()
                    : plugin.seasons().snapshots().keySet().stream().map(UUID::toString).toList();
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }
}
