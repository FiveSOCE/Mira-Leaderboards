package com.mira.leaderboards;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.core.api.PaginationService;
import com.mira.seasons.MiraSeasonsPlugin.MiraSeasonsApi;
import com.mira.seasons.api.event.MiraSeasonStartedEvent;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;

public final class MiraLeaderboardsPlugin extends JavaPlugin implements Listener {
    private static final String PREFIX = "&5&lMira &8>> &r";

    private MiraCore core;
    private BoardService boards;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();
        boards = new BoardService(this);

        getServer().getServicesManager().register(MiraLeaderboardsApi.class, boards, this, ServicePriority.Normal);
        core.modules().register(this, "MiraLeaderboards");
        core.services().register(MiraLeaderboardsApi.class, boards);
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Stable-ID boards, snapshots, season resets and cached Top N ready");

        Objects.requireNonNull(getCommand("leaderboard")).setExecutor(this);
        Objects.requireNonNull(getCommand("mleaderboard")).setExecutor(this);
        Objects.requireNonNull(getCommand("leaderboard")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("mleaderboard")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new BoardPlaceholders(this).register();
        }

        long snapshotTicks = Math.max(1L, getConfig().getLong("snapshot.interval-minutes", 10L)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> boards.snapshotAll("scheduled"), snapshotTicks, snapshotTicks);
        boards.synchronizeSeason(currentSeasonId());

        getLogger().info("MiraLeaderboards v" + getPluginMeta().getVersion() + " enabled with "
                + boards.boardIds().size() + " board(s).");
    }

    @Override
    public void onDisable() {
        if (boards != null) boards.saveAll();
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (boards != null) core.services().unregister(MiraLeaderboardsApi.class, boards);
            core.modules().unregister(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("leaderboard")) {
            if (args.length == 0) {
                msg(sender, "&6Leaderboards: &f" + (boards.boardIds().isEmpty() ? "None" : String.join(", ", boards.boardIds())));
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
            help(sender);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "set", "add" -> {
                if (args.length < 4) {
                    msg(sender, "&e/mlb " + action + " <board> <entry> <score>");
                    return true;
                }
                double value = parseDouble(args[3], Double.NaN);
                if (!Double.isFinite(value)) {
                    msg(sender, "&cInvalid score.");
                    return true;
                }
                if (action.equals("add")) value += boards.score(args[1], args[2]);
                if (!boards.setScore(args[1], args[2], value)) {
                    msg(sender, "&cInvalid board or entry.");
                    return true;
                }
                audit(sender, action.equals("add") ? "LEADERBOARD_SCORE_ADDED" : "LEADERBOARD_SCORE_SET",
                        args[1], args[2], value);
                msg(sender, "&aUpdated &f" + args[2] + " &aon &f" + args[1] + "&a.");
            }
            case "publish" -> {
                if (args.length < 5) {
                    msg(sender, "&e/mlb publish <board> <stable-id> <display-name> <score> [source]");
                    return true;
                }
                double score = parseDouble(args[4], Double.NaN);
                if (!Double.isFinite(score)) {
                    msg(sender, "&cInvalid score.");
                    return true;
                }
                String source = args.length >= 6 ? args[5] : "admin";
                if (!boards.publish(source, args[1], args[2], args[3], score)) {
                    msg(sender, "&cInvalid board, stable ID or score.");
                    return true;
                }
                audit(sender, "LEADERBOARD_SCORE_PUBLISHED", args[1], args[2], score);
                msg(sender, "&aPublished stable entry &f" + args[2] + " &aon &f" + args[1] + "&a.");
            }
            case "remove" -> {
                if (args.length < 3) {
                    msg(sender, "&e/mlb remove <board> <entry-id-or-name>");
                    return true;
                }
                boolean removed = boards.remove(args[1], args[2]);
                msg(sender, removed ? "&aEntry removed." : "&cEntry not found.");
            }
            case "clear" -> {
                if (args.length < 2) {
                    msg(sender, "&e/mlb clear <board>");
                    return true;
                }
                int removed = boards.clear(args[1]);
                core.audit().record("MiraLeaderboards", "LEADERBOARD_CLEARED",
                        sender instanceof Player player ? player.getUniqueId() : null,
                        sender.getName(), args[1], "Cleared leaderboard",
                        Map.of("removed", Integer.toString(removed)));
                msg(sender, "&aLeaderboard cleared. Removed &f" + removed + "&a entries.");
            }
            case "scope" -> {
                if (args.length < 3) {
                    msg(sender, "&e/mlb scope <board> <all_time|seasonal>");
                    return true;
                }
                BoardScope scope = BoardScope.parse(args[2]);
                if (scope == null || !boards.configure(args[1], scope, currentSeasonId())) {
                    msg(sender, "&cUse ALL_TIME or SEASONAL and a valid board ID.");
                    return true;
                }
                msg(sender, "&aBoard &f" + args[1] + " &ais now &f" + scope.name() + "&a.");
            }
            case "snapshot" -> {
                if (args.length < 2) {
                    msg(sender, "&e/mlb snapshot <board|all>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    boards.snapshotAll("manual:" + sender.getName());
                    msg(sender, "&aSnapshot captured for all leaderboards.");
                } else if (boards.snapshot(args[1], "manual:" + sender.getName())) {
                    msg(sender, "&aSnapshot captured for &f" + args[1] + "&a.");
                } else {
                    msg(sender, "&cUnknown leaderboard.");
                }
            }
            case "history" -> {
                if (args.length < 2) {
                    msg(sender, "&e/mlb history <board>");
                    return true;
                }
                showHistory(sender, args[1]);
            }
            case "gui" -> {
                if (!(sender instanceof Player player)) {
                    msg(sender, "&cGUI commands must be run by a player.");
                    return true;
                }
                if (args.length < 2) {
                    msg(sender, "&e/mlb gui <board> [page]");
                    return true;
                }
                openGui(player, args[1], args.length >= 3 ? parseInt(args[2], 1) : 1);
            }
            case "list" -> {
                msg(sender, "&6Mira Leaderboards");
                for (String id : boards.boardIds()) {
                    msg(sender, "&7- &f" + id + " &8[" + boards.scope(id).name() + "] &7"
                            + boards.entryCount(id) + " entries"
                            + boards.seasonId(id).map(season -> " &8season=" + season).orElse(""));
                }
                if (boards.boardIds().isEmpty()) msg(sender, "&7None.");
            }
            default -> help(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("leaderboard")) {
            if (args.length == 1) return complete(args[0], boards.boardIds());
            return List.of();
        }
        if (!sender.hasPermission("miraleaderboards.admin")) return List.of();
        if (args.length == 1) {
            return complete(args[0], List.of("set", "add", "publish", "remove", "clear", "scope",
                    "snapshot", "history", "gui", "list"));
        }
        if (args.length == 2 && Set.of("remove", "clear", "scope", "snapshot", "history", "gui")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> values = new ArrayList<>(boards.boardIds());
            if (args[0].equalsIgnoreCase("snapshot")) values.add("all");
            return complete(args[1], values);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("scope")) {
            return complete(args[2], List.of("all_time", "seasonal"));
        }
        return List.of();
    }

    @EventHandler
    public void onSeasonStarted(MiraSeasonStartedEvent event) {
        if (!getConfig().getBoolean("seasonal.auto-reset-on-season-start", true)) return;
        boards.synchronizeSeason(event.seasonId());
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BoardHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        if (raw == 45 && holder.page() > 1) {
            openGui(player, holder.board(), holder.page() - 1);
        } else if (raw == 49) {
            player.closeInventory();
        } else if (raw == 53) {
            openGui(player, holder.board(), holder.page() + 1);
        }
    }

    private void showText(CommandSender sender, String rawBoard, int requestedPage) {
        String board = BoardService.boardId(rawBoard);
        List<RankedEntry> entries = boards.rankedTop(board, 100);
        if (entries.isEmpty()) {
            msg(sender, "&7No entries for &f" + rawBoard + "&7.");
            return;
        }
        PaginationService.Page<RankedEntry> page = core.pagination().page(entries, requestedPage, 10);
        msg(sender, "&6&l" + board + " &7(Page " + page.page() + "/" + page.pages() + ") &8["
                + boards.scope(board).name() + "]");
        for (RankedEntry entry : page.values()) {
            String delta = entry.rankDelta() == 0 ? ""
                    : entry.rankDelta() > 0 ? " &a▲" + entry.rankDelta()
                    : " &c▼" + Math.abs(entry.rankDelta());
            msg(sender, "&e#" + entry.rank() + " &f" + entry.displayName()
                    + " &8- &a" + format(entry.score()) + delta);
        }
    }

    private void showHistory(CommandSender sender, String rawBoard) {
        String board = BoardService.boardId(rawBoard);
        List<Snapshot> snapshots = boards.snapshots(board, 10);
        if (snapshots.isEmpty()) {
            msg(sender, "&7No snapshots for &f" + rawBoard + "&7.");
            return;
        }
        msg(sender, "&6Snapshot History: &f" + board);
        for (Snapshot snapshot : snapshots) {
            msg(sender, "&7- &f" + new Date(snapshot.timestamp()) + " &8[" + snapshot.reason() + "] "
                    + snapshot.entries().size() + " entries"
                    + (snapshot.seasonId().isBlank() ? "" : " &8season=" + snapshot.seasonId()));
        }
    }

    private void openGui(Player player, String rawBoard, int requestedPage) {
        String board = BoardService.boardId(rawBoard);
        List<RankedEntry> entries = boards.rankedTop(board, 100);
        PaginationService.Page<RankedEntry> page = core.pagination().page(entries, requestedPage, 45);

        Inventory inventory = Bukkit.createInventory(new BoardHolder(board, page.page()), 54,
                c("&5" + board + " Leaderboard"));
        for (int slot = 0; slot < page.values().size(); slot++) {
            RankedEntry entry = page.values().get(slot);
            Material material = switch (entry.rank()) {
                case 1 -> Material.GOLD_BLOCK;
                case 2 -> Material.IRON_BLOCK;
                case 3 -> Material.COPPER_BLOCK;
                default -> Material.PAPER;
            };
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(c("&e#" + entry.rank() + " &f" + entry.displayName()));
            List<String> lore = new ArrayList<>();
            lore.add(c("&7Score: &a" + format(entry.score())));
            lore.add(c("&7Stable ID: &8" + entry.stableId()));
            if (!entry.source().isBlank()) lore.add(c("&7Source: &f" + entry.source()));
            if (entry.scoreDelta() != 0D) lore.add(c("&7Since snapshot: "
                    + (entry.scoreDelta() > 0 ? "&a+" : "&c") + format(entry.scoreDelta())));
            if (entry.rankDelta() != 0) lore.add(c("&7Rank change: "
                    + (entry.rankDelta() > 0 ? "&a+" : "&c") + entry.rankDelta()));
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }

        if (page.hasPrevious()) inventory.setItem(45, control(Material.ARROW, "&ePrevious Page"));
        inventory.setItem(47, control(Material.CLOCK, "&bSnapshot: &f"
                + boards.lastSnapshotAt(board).map(MiraLeaderboardsPlugin::age).orElse("Never")));
        inventory.setItem(49, control(Material.BARRIER, "&cClose"));
        inventory.setItem(51, control(Material.BOOK, "&f" + boards.scope(board).name()
                + boards.seasonId(board).map(id -> " &8| &f" + id).orElse("")));
        if (page.hasNext()) inventory.setItem(53, control(Material.ARROW, "&eNext Page"));
        player.openInventory(inventory);
    }

    private ItemStack control(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(c(name));
        item.setItemMeta(meta);
        return item;
    }

    private void help(CommandSender sender) {
        msg(sender, "&e/mlb set <board> <entry> <score>");
        msg(sender, "&e/mlb add <board> <entry> <delta>");
        msg(sender, "&e/mlb publish <board> <stable-id> <display-name> <score> [source]");
        msg(sender, "&e/mlb remove <board> <entry-id-or-name>");
        msg(sender, "&e/mlb clear <board>");
        msg(sender, "&e/mlb scope <board> <all_time|seasonal>");
        msg(sender, "&e/mlb snapshot <board|all>");
        msg(sender, "&e/mlb history <board>");
        msg(sender, "&e/mlb gui <board> [page]");
        msg(sender, "&e/mlb list");
    }

    private void audit(CommandSender sender, String action, String board, String entry, double score) {
        core.audit().record("MiraLeaderboards", action,
                sender instanceof Player player ? player.getUniqueId() : null,
                sender.getName(), board, "Updated leaderboard score",
                Map.of("entry", entry, "score", Double.toString(score)));
    }

    private String currentSeasonId() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MiraSeasons")) return "";
        RegisteredServiceProvider<MiraSeasonsApi> registration =
                Bukkit.getServicesManager().getRegistration(MiraSeasonsApi.class);
        if (registration == null || registration.getProvider() == null) return "";
        return registration.getProvider().current().map(season -> season.id()).orElse("");
    }

    private void msg(CommandSender sender, String raw) {
        core.messages().send(sender, raw);
    }

    static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception ignored) { return fallback; }
    }

    static double parseDouble(String s, double fallback) {
        try { return Double.parseDouble(s); } catch (Exception ignored) { return fallback; }
    }

    static String format(double value) {
        return new DecimalFormat("#,##0.##").format(value);
    }

    static String age(long timestamp) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60L;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60L;
        if (hours < 24) return hours + "h ago";
        return (hours / 24L) + "d ago";
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted().toList();
    }

    private record BoardHolder(String board, int page) implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); }
    }

    public enum BoardScope {
        ALL_TIME,
        SEASONAL;

        static @Nullable BoardScope parse(String raw) {
            if (raw == null) return null;
            String value = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (value.equals("ALLTIME")) value = "ALL_TIME";
            try { return valueOf(value); }
            catch (IllegalArgumentException ignored) { return null; }
        }
    }

    /** Backward-compatible simple entry used by the original 0.1.0 API. */
    public record Entry(String name, double score) {}

    public record RankedEntry(String stableId, String displayName, double score, int rank,
                              double scoreDelta, int rankDelta, String source) {}

    public record SnapshotEntry(String stableId, String displayName, double score, int rank, String source) {}

    public record Snapshot(long timestamp, String reason, String seasonId, List<SnapshotEntry> entries) {}

    public interface MiraLeaderboardsApi {
        // 0.1.0 compatibility
        void setScore(String board, String entry, double score);
        double score(String board, String entry);
        List<Entry> top(String board, int limit);
        Set<String> boardIds();
        void remove(String board, String entry);
        void clear(String board);

        // 0.1.1 stable publisher API
        boolean setScore(String board, String stableId, String displayName, double score);
        boolean addScore(String board, String stableId, String displayName, double delta);
        boolean publish(String source, String board, String stableId, String displayName, double score);
        List<RankedEntry> rankedTop(String board, int limit);
        Optional<RankedEntry> rankedEntry(String board, String stableIdOrDisplayName);
        int rank(String board, String stableIdOrDisplayName);
        BoardScope scope(String board);
        Optional<String> seasonId(String board);
        boolean configure(String board, BoardScope scope, String seasonId);
        boolean snapshot(String board, String reason);
        List<Snapshot> snapshots(String board, int limit);
    }

    static final class BoardService implements MiraLeaderboardsApi {
        private final MiraLeaderboardsPlugin plugin;
        private final File boardFile;
        private final File snapshotFile;
        private final Map<String, Board> boards = new LinkedHashMap<>();
        private final Map<String, List<Snapshot>> history = new LinkedHashMap<>();
        private final Map<String, List<RankedEntry>> topCache = new HashMap<>();

        BoardService(MiraLeaderboardsPlugin plugin) {
            this.plugin = plugin;
            this.boardFile = new File(plugin.getDataFolder(), "leaderboards.yml");
            this.snapshotFile = new File(plugin.getDataFolder(), "snapshots.yml");
            loadBoards();
            loadSnapshots();
        }

        static String boardId(String raw) {
            if (raw == null) return "";
            String id = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            return id.matches("[a-z0-9_-]{1,64}") ? id : "";
        }

        private static String stable(String raw) {
            return raw == null ? "" : raw.trim();
        }

        private static String storageKey(String stableId) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(stableId.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized void setScore(String board, String entry, double score) {
            setScore(board, "legacy:" + entry.toLowerCase(Locale.ROOT), entry, score);
        }

        @Override
        public synchronized boolean setScore(String board, String stableId, String displayName, double score) {
            return publish("api", board, stableId, displayName, score);
        }

        @Override
        public synchronized boolean addScore(String board, String stableId, String displayName, double delta) {
            if (!Double.isFinite(delta)) return false;
            String id = boardId(board);
            String sid = stable(stableId);
            if (id.isBlank() || sid.isBlank()) return false;
            Board target = boards.computeIfAbsent(id, ignored -> new Board(id));
            BoardEntry current = target.entries.get(sid);
            double next = (current == null ? 0D : current.score) + delta;
            return publish(current == null ? "api" : current.source, id, sid, displayName, next);
        }

        @Override
        public synchronized boolean publish(String source, String board, String stableId,
                                            String displayName, double score) {
            String id = boardId(board);
            String sid = stable(stableId);
            if (id.isBlank() || sid.isBlank() || !Double.isFinite(score)) return false;
            Board target = boards.computeIfAbsent(id, ignored -> new Board(id));
            String shown = displayName == null || displayName.isBlank() ? sid : displayName.trim();
            String src = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
            target.entries.put(sid, new BoardEntry(sid, shown, score, src));
            invalidate(id);
            saveBoards();
            return true;
        }

        @Override
        public synchronized double score(String board, String entry) {
            BoardEntry found = find(board, entry);
            return found == null ? 0D : found.score;
        }

        @Override
        public synchronized List<Entry> top(String board, int limit) {
            return rankedTop(board, limit).stream()
                    .map(entry -> new Entry(entry.displayName(), entry.score()))
                    .toList();
        }

        @Override
        public synchronized List<RankedEntry> rankedTop(String board, int limit) {
            String id = boardId(board);
            if (id.isBlank() || limit <= 0) return List.of();
            int cacheLimit = Math.max(10, plugin.getConfig().getInt("cache-top-n", 100));
            List<RankedEntry> cached = topCache.computeIfAbsent(id, ignored -> buildRanking(id, cacheLimit));
            if (limit <= cached.size() || cached.size() < cacheLimit) {
                return List.copyOf(cached.subList(0, Math.min(limit, cached.size())));
            }
            return buildRanking(id, limit);
        }

        @Override
        public synchronized Optional<RankedEntry> rankedEntry(String board, String stableIdOrDisplayName) {
            String id = boardId(board);
            BoardEntry wanted = find(id, stableIdOrDisplayName);
            if (wanted == null) return Optional.empty();
            return buildRanking(id, Integer.MAX_VALUE).stream()
                    .filter(entry -> entry.stableId().equals(wanted.stableId))
                    .findFirst();
        }

        @Override
        public synchronized int rank(String board, String stableIdOrDisplayName) {
            return rankedEntry(board, stableIdOrDisplayName).map(RankedEntry::rank).orElse(0);
        }

        @Override
        public synchronized Set<String> boardIds() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(boards.keySet()));
        }

        @Override
        public synchronized boolean remove(String board, String entry) {
            String id = boardId(board);
            Board target = boards.get(id);
            if (target == null) return false;
            BoardEntry found = find(id, entry);
            if (found == null) return false;
            target.entries.remove(found.stableId);
            invalidate(id);
            saveBoards();
            return true;
        }

        @Override
        public synchronized void remove(String board, String entry) {
            remove(board, entry);
        }

        @Override
        public synchronized int clear(String board) {
            String id = boardId(board);
            Board target = boards.get(id);
            if (target == null) return 0;
            int count = target.entries.size();
            target.entries.clear();
            invalidate(id);
            saveBoards();
            return count;
        }

        @Override
        public synchronized void clear(String board) {
            clear(board);
        }

        @Override
        public synchronized BoardScope scope(String board) {
            Board target = boards.get(boardId(board));
            return target == null ? BoardScope.ALL_TIME : target.scope;
        }

        @Override
        public synchronized Optional<String> seasonId(String board) {
            Board target = boards.get(boardId(board));
            return target == null || target.seasonId.isBlank() ? Optional.empty() : Optional.of(target.seasonId);
        }

        @Override
        public synchronized boolean configure(String board, BoardScope scope, String seasonId) {
            String id = boardId(board);
            if (id.isBlank() || scope == null) return false;
            Board target = boards.computeIfAbsent(id, ignored -> new Board(id));
            target.scope = scope;
            target.seasonId = scope == BoardScope.SEASONAL && seasonId != null ? seasonId : "";
            invalidate(id);
            saveBoards();
            return true;
        }

        synchronized int entryCount(String board) {
            Board target = boards.get(boardId(board));
            return target == null ? 0 : target.entries.size();
        }

        @Override
        public synchronized boolean snapshot(String board, String reason) {
            String id = boardId(board);
            if (!boards.containsKey(id)) return false;
            List<RankedEntry> ranked = buildRanking(id, Math.max(1, plugin.getConfig().getInt("cache-top-n", 100)));
            List<SnapshotEntry> entries = ranked.stream()
                    .map(entry -> new SnapshotEntry(entry.stableId(), entry.displayName(), entry.score(),
                            entry.rank(), entry.source()))
                    .toList();
            Snapshot snapshot = new Snapshot(System.currentTimeMillis(),
                    reason == null ? "manual" : reason,
                    seasonId(id).orElse(""), entries);
            List<Snapshot> list = history.computeIfAbsent(id, ignored -> new ArrayList<>());
            list.add(snapshot);
            int keep = Math.max(1, plugin.getConfig().getInt("snapshot.history-limit", 48));
            while (list.size() > keep) list.removeFirst();
            invalidate(id);
            saveSnapshots();
            return true;
        }

        synchronized void snapshotAll(String reason) {
            for (String board : new ArrayList<>(boards.keySet())) snapshot(board, reason);
        }

        @Override
        public synchronized List<Snapshot> snapshots(String board, int limit) {
            List<Snapshot> values = history.getOrDefault(boardId(board), List.of());
            if (values.isEmpty() || limit <= 0) return List.of();
            int start = Math.max(0, values.size() - limit);
            List<Snapshot> out = new ArrayList<>(values.subList(start, values.size()));
            Collections.reverse(out);
            return List.copyOf(out);
        }

        synchronized Optional<Long> lastSnapshotAt(String board) {
            List<Snapshot> values = history.get(boardId(board));
            return values == null || values.isEmpty()
                    ? Optional.empty()
                    : Optional.of(values.getLast().timestamp());
        }

        synchronized void synchronizeSeason(String newSeasonId) {
            if (newSeasonId == null || newSeasonId.isBlank()) return;
            boolean changed = false;
            for (Board board : boards.values()) {
                if (board.scope != BoardScope.SEASONAL) continue;
                if (board.seasonId.isBlank()) {
                    board.seasonId = newSeasonId;
                    changed = true;
                    continue;
                }
                if (board.seasonId.equalsIgnoreCase(newSeasonId)) continue;

                snapshot(board.id, "season-end:" + board.seasonId);
                int removed = board.entries.size();
                board.entries.clear();
                String previous = board.seasonId;
                board.seasonId = newSeasonId;
                invalidate(board.id);
                changed = true;
                plugin.core.audit().record("MiraLeaderboards", "SEASONAL_BOARD_RESET", null, "scheduler",
                        board.id, "Reset seasonal leaderboard",
                        Map.of("previousSeason", previous, "newSeason", newSeasonId,
                                "removedEntries", Integer.toString(removed)));
            }
            if (changed) saveBoards();
        }

        private List<RankedEntry> buildRanking(String board, int limit) {
            Board target = boards.get(board);
            if (target == null || target.entries.isEmpty()) return List.of();

            Snapshot previous = latestSnapshot(board);
            Map<String, SnapshotEntry> previousById = new HashMap<>();
            if (previous != null) {
                for (SnapshotEntry entry : previous.entries()) previousById.put(entry.stableId(), entry);
            }

            List<BoardEntry> sorted = target.entries.values().stream()
                    .sorted(Comparator.comparingDouble((BoardEntry entry) -> entry.score).reversed()
                            .thenComparing(entry -> entry.displayName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(entry -> entry.stableId))
                    .limit(Math.max(1, limit))
                    .toList();

            List<RankedEntry> out = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                BoardEntry current = sorted.get(i);
                int currentRank = i + 1;
                SnapshotEntry old = previousById.get(current.stableId);
                double scoreDelta = old == null ? 0D : current.score - old.score();
                int rankDelta = old == null ? 0 : old.rank() - currentRank;
                out.add(new RankedEntry(current.stableId, current.displayName, current.score,
                        currentRank, scoreDelta, rankDelta, current.source));
            }
            return List.copyOf(out);
        }

        private Snapshot latestSnapshot(String board) {
            List<Snapshot> values = history.get(board);
            return values == null || values.isEmpty() ? null : values.getLast();
        }

        private BoardEntry find(String board, String stableIdOrDisplayName) {
            Board target = boards.get(boardId(board));
            if (target == null || stableIdOrDisplayName == null) return null;
            BoardEntry direct = target.entries.get(stableIdOrDisplayName);
            if (direct != null) return direct;
            return target.entries.values().stream()
                    .filter(entry -> entry.stableId.equalsIgnoreCase(stableIdOrDisplayName)
                            || entry.displayName.equalsIgnoreCase(stableIdOrDisplayName))
                    .findFirst().orElse(null);
        }

        private void invalidate(String board) {
            topCache.remove(board);
        }

        private void loadBoards() {
            boards.clear();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(boardFile);
            ConfigurationSection root = yaml.getConfigurationSection("boards");
            if (root == null) return;

            for (String rawBoard : root.getKeys(false)) {
                String id = boardId(rawBoard);
                if (id.isBlank()) continue;
                ConfigurationSection section = root.getConfigurationSection(rawBoard);
                if (section == null) continue;

                Board board = new Board(id);
                BoardScope parsed = BoardScope.parse(section.getString("scope", "ALL_TIME"));
                board.scope = parsed == null ? BoardScope.ALL_TIME : parsed;
                board.seasonId = section.getString("season-id", "");

                ConfigurationSection entries = section.getConfigurationSection("entries");
                if (entries != null) {
                    for (String storage : entries.getKeys(false)) {
                        String path = "entries." + storage;
                        String stableId = section.getString(path + ".id", "");
                        if (stableId.isBlank()) continue;
                        String display = section.getString(path + ".display-name", stableId);
                        String source = section.getString(path + ".source", "");
                        double score = section.getDouble(path + ".score", 0D);
                        if (Double.isFinite(score)) {
                            board.entries.put(stableId, new BoardEntry(stableId, display, score, source));
                        }
                    }
                } else {
                    // 0.1.0 migration: boards.<board>.<display-name>: score
                    for (String legacy : section.getKeys(false)) {
                        if (Set.of("scope", "season-id").contains(legacy)) continue;
                        Object value = section.get(legacy);
                        if (!(value instanceof Number number)) continue;
                        String stableId = "legacy:" + legacy.toLowerCase(Locale.ROOT);
                        board.entries.put(stableId, new BoardEntry(stableId, legacy,
                                number.doubleValue(), "legacy"));
                    }
                }
                boards.put(id, board);
            }
        }

        private void saveBoards() {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Board board : boards.values()) {
                String base = "boards." + board.id;
                yaml.set(base + ".scope", board.scope.name());
                yaml.set(base + ".season-id", board.seasonId.isBlank() ? null : board.seasonId);
                for (BoardEntry entry : board.entries.values()) {
                    String path = base + ".entries." + storageKey(entry.stableId);
                    yaml.set(path + ".id", entry.stableId);
                    yaml.set(path + ".display-name", entry.displayName);
                    yaml.set(path + ".source", entry.source.isBlank() ? null : entry.source);
                    yaml.set(path + ".score", entry.score);
                }
            }
            try {
                yaml.save(boardFile);
            } catch (IOException exception) {
                plugin.getLogger().severe("Failed to save leaderboards.yml: " + exception.getMessage());
            }
        }

        @SuppressWarnings("unchecked")
        private void loadSnapshots() {
            history.clear();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(snapshotFile);
            ConfigurationSection root = yaml.getConfigurationSection("boards");
            if (root == null) return;

            for (String rawBoard : root.getKeys(false)) {
                String id = boardId(rawBoard);
                if (id.isBlank()) continue;
                List<Snapshot> list = new ArrayList<>();
                for (Map<?, ?> raw : yaml.getMapList("boards." + rawBoard)) {
                    try {
                        long timestamp = longValue(raw.get("timestamp"));
                        String reason = String.valueOf(raw.get("reason"));
                        String season = raw.get("season") == null ? "" : String.valueOf(raw.get("season"));
                        List<SnapshotEntry> entries = new ArrayList<>();
                        Object rawEntries = raw.get("entries");
                        if (rawEntries instanceof List<?> rows) {
                            for (Object rowObject : rows) {
                                if (!(rowObject instanceof Map<?, ?> row)) continue;
                                String stableId = String.valueOf(row.get("id"));
                                String display = String.valueOf(row.get("display"));
                                double score = doubleValue(row.get("score"));
                                int rank = (int) longValue(row.get("rank"));
                                String source = row.get("source") == null ? "" : String.valueOf(row.get("source"));
                                if (!stableId.isBlank() && rank > 0 && Double.isFinite(score)) {
                                    entries.add(new SnapshotEntry(stableId, display, score, rank, source));
                                }
                            }
                        }
                        if (timestamp > 0) list.add(new Snapshot(timestamp, reason, season, List.copyOf(entries)));
                    } catch (RuntimeException ignored) {
                    }
                }
                list.sort(Comparator.comparingLong(Snapshot::timestamp));
                if (!list.isEmpty()) history.put(id, list);
            }
        }

        private void saveSnapshots() {
            YamlConfiguration yaml = new YamlConfiguration();
            for (var board : history.entrySet()) {
                List<Map<String, Object>> snapshots = new ArrayList<>();
                for (Snapshot snapshot : board.getValue()) {
                    Map<String, Object> raw = new LinkedHashMap<>();
                    raw.put("timestamp", snapshot.timestamp());
                    raw.put("reason", snapshot.reason());
                    raw.put("season", snapshot.seasonId().isBlank() ? null : snapshot.seasonId());
                    List<Map<String, Object>> entries = new ArrayList<>();
                    for (SnapshotEntry entry : snapshot.entries()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", entry.stableId());
                        row.put("display", entry.displayName());
                        row.put("score", entry.score());
                        row.put("rank", entry.rank());
                        row.put("source", entry.source().isBlank() ? null : entry.source());
                        entries.add(row);
                    }
                    raw.put("entries", entries);
                    snapshots.add(raw);
                }
                yaml.set("boards." + board.getKey(), snapshots);
            }
            try {
                yaml.save(snapshotFile);
            } catch (IOException exception) {
                plugin.getLogger().severe("Failed to save snapshots.yml: " + exception.getMessage());
            }
        }

        synchronized void saveAll() {
            saveBoards();
            saveSnapshots();
        }

        private static long longValue(Object value) {
            if (value instanceof Number number) return number.longValue();
            try { return Long.parseLong(String.valueOf(value)); }
            catch (RuntimeException ignored) { return 0L; }
        }

        private static double doubleValue(Object value) {
            if (value instanceof Number number) return number.doubleValue();
            try { return Double.parseDouble(String.valueOf(value)); }
            catch (RuntimeException ignored) { return 0D; }
        }

        private static final class Board {
            private final String id;
            private BoardScope scope = BoardScope.ALL_TIME;
            private String seasonId = "";
            private final Map<String, BoardEntry> entries = new LinkedHashMap<>();

            private Board(String id) { this.id = id; }
        }

        private static final class BoardEntry {
            private final String stableId;
            private final String displayName;
            private final double score;
            private final String source;

            private BoardEntry(String stableId, String displayName, double score, String source) {
                this.stableId = stableId;
                this.displayName = displayName;
                this.score = score;
                this.source = source;
            }
        }
    }

    static final class BoardPlaceholders extends PlaceholderExpansion {
        private final MiraLeaderboardsPlugin plugin;

        BoardPlaceholders(MiraLeaderboardsPlugin plugin) {
            this.plugin = plugin;
        }

        @Override public @NotNull String getIdentifier() { return "miraleaderboards"; }
        @Override public @NotNull String getAuthor() { return "FiveS"; }
        @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
        @Override public boolean persist() { return true; }

        @Override
        public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            String lower = params.toLowerCase(Locale.ROOT);

            int topPos = lower.lastIndexOf("_top_");
            if (topPos > 0) {
                String board = lower.substring(0, topPos);
                String tail = lower.substring(topPos + 5);
                String[] bits = tail.split("_", 2);
                if (bits.length != 2) return null;
                int rank = parseInt(bits[0], -1);
                if (rank < 1 || rank > 100) return null;
                List<RankedEntry> top = plugin.boards.rankedTop(board, rank);
                if (top.size() < rank) return "";
                RankedEntry entry = top.get(rank - 1);
                return switch (bits[1]) {
                    case "id" -> entry.stableId();
                    case "name" -> entry.displayName();
                    case "score" -> Double.toString(entry.score());
                    case "formatted" -> format(entry.score());
                    case "delta" -> Double.toString(entry.scoreDelta());
                    case "rank_delta" -> Integer.toString(entry.rankDelta());
                    case "source" -> entry.source();
                    default -> null;
                };
            }

            for (String suffix : List.of("_player_rank", "_player_score", "_player_formatted",
                    "_player_delta", "_player_rank_delta")) {
                if (!lower.endsWith(suffix)) continue;
                String board = lower.substring(0, lower.length() - suffix.length());
                if (player == null) return "";
                RankedEntry entry = plugin.boards.rankedEntry(board, player.getUniqueId().toString())
                        .or(() -> plugin.boards.rankedEntry(board, player.getName() == null ? "" : player.getName()))
                        .orElse(null);
                if (entry == null) return suffix.equals("_player_rank") ? "0" : "";
                return switch (suffix) {
                    case "_player_rank" -> Integer.toString(entry.rank());
                    case "_player_score" -> Double.toString(entry.score());
                    case "_player_formatted" -> format(entry.score());
                    case "_player_delta" -> Double.toString(entry.scoreDelta());
                    case "_player_rank_delta" -> Integer.toString(entry.rankDelta());
                    default -> null;
                };
            }

            for (String suffix : List.of("_scope", "_season", "_snapshot_age")) {
                if (!lower.endsWith(suffix)) continue;
                String board = lower.substring(0, lower.length() - suffix.length());
                return switch (suffix) {
                    case "_scope" -> plugin.boards.scope(board).name();
                    case "_season" -> plugin.boards.seasonId(board).orElse("");
                    case "_snapshot_age" -> plugin.boards.lastSnapshotAt(board)
                            .map(MiraLeaderboardsPlugin::age).orElse("never");
                    default -> null;
                };
            }
            return null;
        }
    }
}
