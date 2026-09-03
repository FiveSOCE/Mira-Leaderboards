package com.mira.leaderboards;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public final class MiraLeaderboardsPlugin extends JavaPlugin {
    private static final String PREFIX = "&5&lMira &8>> &r";
    private BoardService boards;

    @Override
    public void onEnable() {
        boards = new BoardService(this);
        getServer().getServicesManager().register(MiraLeaderboardsApi.class, boards, this, ServicePriority.Normal);
        Objects.requireNonNull(getCommand("leaderboard")).setExecutor(this);
        Objects.requireNonNull(getCommand("mleaderboard")).setExecutor(this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new BoardPlaceholders(this).register();
        getLogger().info("MiraLeaderboards v" + getDescription().getVersion() + " enabled.");
    }

    @Override public void onDisable() {
        if (boards != null) boards.save();
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("leaderboard")) {
            if (args.length == 0) {
                msg(sender, "&6Leaderboards: &f" + String.join(", ", boards.boardIds()));
                return true;
            }
            int page = args.length >= 2 ? parseInt(args[1], 1) : 1;
            showText(sender, args[0], page);
            return true;
        }
        if (!sender.hasPermission("miraleaderboards.admin")) {
            msg(sender, "&cYou do not have permission.");
            return true;
        }
        if (args.length == 0) {
            msg(sender, "&e/mlb set <board> <entry> <score>");
            msg(sender, "&e/mlb add <board> <entry> <delta>");
            msg(sender, "&e/mlb remove <board> <entry>");
            msg(sender, "&e/mlb clear <board>");
            msg(sender, "&e/mlb gui <board>");
            msg(sender, "&e/mlb list");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set", "add" -> {
                if (args.length < 4) return false;
                double score;
                try { score = Double.parseDouble(args[3]); } catch (NumberFormatException e) { msg(sender, "&cInvalid score."); return true; }
                if (args[0].equalsIgnoreCase("add")) score += boards.score(args[1], args[2]);
                boards.setScore(args[1], args[2], score);
                msg(sender, "&aUpdated &f" + args[2] + " &aon &f" + args[1] + "&a.");
            }
            case "remove" -> {
                if (args.length < 3) return false;
                boards.remove(args[1], args[2]);
                msg(sender, "&aEntry removed.");
            }
            case "clear" -> {
                if (args.length < 2) return false;
                boards.clear(args[1]);
                msg(sender, "&aLeaderboard cleared.");
            }
            case "gui" -> {
                if (!(sender instanceof Player player) || args.length < 2) return true;
                openGui(player, args[1]);
            }
            case "list" -> msg(sender, "&6Leaderboards: &f" + String.join(", ", boards.boardIds()));
            default -> msg(sender, "&cUnknown subcommand.");
        }
        return true;
    }

    private void showText(CommandSender sender, String id, int page) {
        List<Entry> entries = boards.top(id, 100);
        if (entries.isEmpty()) { msg(sender, "&7No entries for &f" + id + "&7."); return; }
        int perPage = 10;
        int maxPage = Math.max(1, (entries.size() + perPage - 1) / perPage);
        page = Math.max(1, Math.min(page, maxPage));
        msg(sender, "&6&l" + id + " &7(Page " + page + "/" + maxPage + ")");
        int start = (page - 1) * perPage;
        for (int i = start; i < Math.min(entries.size(), start + perPage); i++) {
            Entry e = entries.get(i);
            msg(sender, "&e#" + (i + 1) + " &f" + e.name() + " &8- &a" + format(e.score()));
        }
    }

    private void openGui(Player player, String id) {
        Inventory inv = Bukkit.createInventory(null, 27, c("&6" + id + " Leaderboard"));
        List<Entry> top = boards.top(id, 10);
        int[] slots = {4, 12, 14, 10, 16, 19, 20, 22, 24, 25};
        for (int i = 0; i < top.size() && i < slots.length; i++) {
            Entry e = top.get(i);
            ItemStack item = new ItemStack(i == 0 ? Material.GOLD_BLOCK : i == 1 ? Material.IRON_BLOCK : i == 2 ? Material.COPPER_BLOCK : Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(c("&e#" + (i + 1) + " &f" + e.name()));
            meta.setLore(List.of(c("&7Score: &a" + format(e.score()))));
            item.setItemMeta(meta);
            inv.setItem(slots[i], item);
        }
        player.openInventory(inv);
    }

    private void msg(CommandSender sender, String raw) { sender.sendMessage(c(PREFIX + raw)); }
    static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    static int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (Exception e) { return fallback; } }
    static String format(double v) { return new DecimalFormat("#,##0.##").format(v); }

    public record Entry(String name, double score) {}

    public interface MiraLeaderboardsApi {
        void setScore(String board, String entry, double score);
        double score(String board, String entry);
        List<Entry> top(String board, int limit);
        Set<String> boardIds();
        void remove(String board, String entry);
        void clear(String board);
    }

    static final class BoardService implements MiraLeaderboardsApi {
        private final MiraLeaderboardsPlugin plugin;
        private final File file;
        private final Map<String, Map<String, Double>> data = new LinkedHashMap<>();
        BoardService(MiraLeaderboardsPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "leaderboards.yml");
            load();
        }
        synchronized void load() {
            data.clear();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            var root = yaml.getConfigurationSection("boards");
            if (root == null) return;
            for (String board : root.getKeys(false)) {
                Map<String, Double> scores = new LinkedHashMap<>();
                var sec = root.getConfigurationSection(board);
                if (sec != null) for (String entry : sec.getKeys(false)) scores.put(entry, sec.getDouble(entry));
                data.put(board.toLowerCase(Locale.ROOT), scores);
            }
        }
        @Override public synchronized void setScore(String board, String entry, double score) {
            data.computeIfAbsent(board.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>()).put(entry, score);
            save();
        }
        @Override public synchronized double score(String board, String entry) {
            Map<String, Double> map = data.get(board.toLowerCase(Locale.ROOT));
            if (map == null) return 0;
            return map.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(entry)).mapToDouble(Map.Entry::getValue).findFirst().orElse(0);
        }
        @Override public synchronized List<Entry> top(String board, int limit) {
            Map<String, Double> map = data.get(board.toLowerCase(Locale.ROOT));
            if (map == null) return List.of();
            return map.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).sorted(Comparator.comparingDouble(Entry::score).reversed().thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).limit(Math.max(1, limit)).toList();
        }
        @Override public synchronized Set<String> boardIds() { return new LinkedHashSet<>(data.keySet()); }
        @Override public synchronized void remove(String board, String entry) {
            Map<String, Double> map = data.get(board.toLowerCase(Locale.ROOT));
            if (map != null) map.keySet().removeIf(k -> k.equalsIgnoreCase(entry));
            save();
        }
        @Override public synchronized void clear(String board) { data.remove(board.toLowerCase(Locale.ROOT)); save(); }
        synchronized void save() {
            YamlConfiguration yaml = new YamlConfiguration();
            for (var board : data.entrySet()) for (var entry : board.getValue().entrySet()) yaml.set("boards." + board.getKey() + "." + entry.getKey(), entry.getValue());
            try { yaml.save(file); } catch (IOException e) { plugin.getLogger().severe("Failed to save leaderboards.yml: " + e.getMessage()); }
        }
    }

    static final class BoardPlaceholders extends PlaceholderExpansion {
        private final MiraLeaderboardsPlugin plugin;
        BoardPlaceholders(MiraLeaderboardsPlugin plugin) { this.plugin = plugin; }
        @Override public @NotNull String getIdentifier() { return "miraleaderboards"; }
        @Override public @NotNull String getAuthor() { return "FiveS"; }
        @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
        @Override public boolean persist() { return true; }
        @Override public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            String lower = params.toLowerCase(Locale.ROOT);
            int topPos = lower.lastIndexOf("_top_");
            if (topPos < 1) return null;
            String board = lower.substring(0, topPos);
            String tail = lower.substring(topPos + 5);
            String[] bits = tail.split("_", 2);
            if (bits.length != 2) return null;
            int rank = parseInt(bits[0], -1);
            if (rank < 1 || rank > 100) return null;
            List<Entry> top = plugin.boards.top(board, rank);
            if (top.size() < rank) return "";
            Entry e = top.get(rank - 1);
            return switch (bits[1]) {
                case "name" -> e.name();
                case "score" -> Double.toString(e.score());
                case "formatted" -> format(e.score());
                default -> null;
            };
        }
    }
}
