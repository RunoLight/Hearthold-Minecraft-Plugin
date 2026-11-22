package dev.hearthold.web;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.economy.EconomyService;
import dev.hearthold.i18n.Lang;
import dev.hearthold.trade.TradeRoute;
import dev.hearthold.trade.TradeService;
import dev.hearthold.trade.Treasury;
import dev.hearthold.war.War;
import dev.hearthold.war.WarService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Website admin panel data (operators only): block-breaking economy per tool tier in the wilderness and on bases
 * (in and outside prime time), energy, collectors and a clan summary. Collected on the main thread.
 */
public final class AdminWebData {

    /** Tool tiers: damage key in the config and material prefix. */
    private record Tier(String key, String prefix) {
        String title() {
            return Lang.tr("web.admin.tier." + key);
        }
    }

    private static final List<Tier> TIERS = List.of(
            new Tier("wooden", "WOODEN"), new Tier("stone", "STONE"),
            new Tier("copper", "COPPER"), new Tier("iron", "IRON"),
            new Tier("golden", "GOLDEN"), new Tier("diamond", "DIAMOND"),
            new Tier("netherite", "NETHERITE"));

    private static final List<Material> WILD_BLOCKS = List.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.SAND, Material.GRAVEL,
            Material.OAK_LOG, Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE,
            Material.NETHER_BRICKS, Material.QUARTZ_BLOCK, Material.TERRACOTTA, Material.SANDSTONE,
            Material.COAL_ORE, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.DIAMOND_ORE,
            Material.NETHERRACK, Material.OBSIDIAN,
            Material.OAK_DOOR, Material.IRON_DOOR, Material.OAK_TRAPDOOR, Material.IRON_TRAPDOOR);

    public static final List<Material> BASE_BLOCKS = List.of(
            Material.OAK_PLANKS, Material.COBBLESTONE, Material.STONE_BRICKS, Material.DEEPSLATE_BRICKS,
            Material.NETHER_BRICKS, Material.QUARTZ_BLOCK, Material.TERRACOTTA,
            Material.IRON_BLOCK, Material.OBSIDIAN,
            Material.OAK_DOOR, Material.IRON_DOOR, Material.OAK_TRAPDOOR, Material.IRON_TRAPDOOR);

    /** War fatigue levels for which regeneration time is computed. */
    private static final double[] FATIGUE_LEVELS = {0, 1, 2};

    /** Vanilla delay between blocks when mining, in ticks. */
    private static final int DESTROY_DELAY = 5;

    private final ClanService clans;
    private final EconomyService economy;
    private final WarService wars;
    private final TradeService trade;
    private final Treasury treasury;
    private final Supplier<HeartholdConfig> config;

    private volatile Map<String, Object> data = Map.of();
    private dev.hearthold.base.BaseLevelService levels;

    /** Special state of a clan base (abandoned, heart being removed) or null. */
    private java.util.function.Function<java.util.UUID, String> baseNotice = id -> null;

    public void setBaseNotice(java.util.function.Function<java.util.UUID, String> baseNotice) {
        this.baseNotice = baseNotice;
    }

    private java.util.function.BooleanSupplier fastCapture = () -> false;

    public void setFastCapture(java.util.function.BooleanSupplier fastCapture) {
        this.fastCapture = fastCapture;
    }

    public void setLevels(dev.hearthold.base.BaseLevelService levels) {
        this.levels = levels;
    }
    private volatile Set<UUID> operators = Set.of();

    public AdminWebData(ClanService clans, EconomyService economy, WarService wars, TradeService trade,
                        Treasury treasury, Supplier<HeartholdConfig> config) {
        this.clans = clans;
        this.economy = economy;
        this.wars = wars;
        this.trade = trade;
        this.treasury = treasury;
        this.config = config;
    }

    Map<String, Object> data() {
        return data;
    }

    boolean isOperator(UUID player) {
        return operators.contains(player);
    }

    /** Called every 30 seconds on the main thread. */
    public void refresh() {
        operators = Bukkit.getOperators().stream().map(OfflinePlayer::getUniqueId).collect(Collectors.toUnmodifiableSet());
        HeartholdConfig c = config.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tiers", TIERS.stream().map(Tier::title).toList());
        m.put("wild", wildTable(c));
        m.put("base", baseTable(c));
        m.put("clans", clanTable());
        m.put("energy", energyStats(c));
        m.put("collectors", collectorStats(c));
        var ws = c.war();
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("seconds", ws.captureSeconds());
        cap.put("radius", ws.captureRadius());
        cap.put("decay", ws.captureDecay());
        cap.put("fast", fastCapture.getAsBoolean());
        cap.put("fastSeconds", dev.hearthold.war.CaptureTask.FAST_SECONDS);
        cap.put("vassalDays", ws.vassalDays());
        cap.put("shieldHours", ws.shieldHours());
        cap.put("offPrimeWear", c.offPrimeWear());
        m.put("capture", cap);
        m.put("at", System.currentTimeMillis());
        data = m;
    }

    // --- Breaking economy ---

    private List<Object> wildTable(HeartholdConfig c) {
        List<Object> rows = new ArrayList<>();
        for (Material block : WILD_BLOCKS) {
            int hp = c.wildHp(block);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("block", name(block));
            row.put("hp", hp);
            row.put("vanilla", hp <= 1);
            // Full recovery after a hit down to 1 HP: pause + HP growth.
            row.put("regenSeconds", hp <= 1 ? null : regenSeconds(c, hp - 1, true, 1.0));
            row.put("cells", cells(c, block, hp, false, c.wildDurabilityMultiplier(), 1));
            rows.add(row);
        }
        return rows;
    }

    /** One table per base level: in and outside prime time. */
    private List<Object> baseTable(HeartholdConfig c) {
        List<Object> levels = new ArrayList<>();
        for (HeartholdConfig.BaseLevel lv : c.baseLevels()) {
            int hp = lv.hp();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", lv.name());
            m.put("hp", hp);
            m.put("upgradeCost", lv.upgradeCost());
            m.put("upkeepPerDay", lv.upkeepPerDay());
            List<Object> prime = new ArrayList<>();
            List<Object> off = new ArrayList<>();
            for (Material block : BASE_BLOCKS) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("block", name(block));
                p.put("hp", hp);
                p.put("cells", cells(c, block, hp, false, c.durabilityMultiplier(), 1));
                prime.add(p);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("block", name(block));
                o.put("hp", hp);
                o.put("cells", cells(c, block, hp, true, c.durabilityMultiplier(), c.offPrimeWear()));
                off.add(o);
            }
            m.put("prime", prime);
            m.put("offPrime", off);
            m.put("regen", regenTable(c, hp));
            levels.add(m);
        }
        return levels;
    }

    /** Seconds to recover {@code amount} HP: pause after a hit (if any) + growth adjusted for fatigue. */
    private static long regenSeconds(HeartholdConfig c, double amount, boolean afterHit, double multiplier) {
        double rate = c.regenPerSecond() * multiplier;
        if (rate <= 0) {
            return -1;
        }
        return Math.round((afterHit ? c.regenDelaySeconds() : 0) + amount / rate);
    }

    /** Regeneration on a base of this level: rows are scenarios, columns are the owner's war fatigue. */
    private Map<String, Object> regenTable(HeartholdConfig c, int hp) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> fatigue = new ArrayList<>();
        for (double f : FATIGUE_LEVELS) {
            fatigue.add(f);
        }
        m.put("fatigue", fatigue);
        List<Object> rows = new ArrayList<>();
        Object[][] scenarios = {
                {Lang.tr("web.admin.regen.new", hp), hp - 1.0, false},
                {Lang.tr("web.admin.regen.half", hp), hp / 2.0, true},
                {Lang.tr("web.admin.regen.low", hp), hp - 1.0, true}};
        for (Object[] sc : scenarios) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", sc[0]);
            List<Object> secs = new ArrayList<>();
            for (double f : FATIGUE_LEVELS) {
                double mult = 1 / (1 + f * c.war().fatigueRegen());
                secs.add(regenSeconds(c, (Double) sc[1], (Boolean) sc[2], mult));
            }
            row.put("seconds", secs);
            rows.add(row);
        }
        m.put("rows", rows);
        m.put("perSecond", c.regenPerSecond());
        m.put("delaySeconds", c.regenDelaySeconds());
        return m;
    }

    /** Row cells, one per tool tier. {@code scale} is the wear multiplier (off-prime-wear outside prime time on a base). */
    private List<Object> cells(HeartholdConfig c, Material block, int hp, boolean onePerHit, double wearMultiplier,
                               double scale) {
        List<Object> cells = new ArrayList<>();
        BlockData data = block.createBlockData();
        for (Tier t : TIERS) {
            Material tool = toolFor(data, t);
            Map<String, Object> cell = new LinkedHashMap<>();
            if (tool == null) {
                cells.add(null);
                continue;
            }
            int damage = onePerHit ? 1 : Math.max(1, c.damageFor(tool));
            int hits = Math.max(1, (int) Math.ceil(hp / (double) damage));
            // Wear = HP removed (+ multiplier per hit); the last hit includes the vanilla point.
            int wear = 0;
            int left = hp;
            for (int i = 0; i < hits; i++) {
                int dealt = Math.min(damage, left);
                left -= dealt;
                wear += (int) Math.ceil((dealt + (int) Math.ceil(dealt * wearMultiplier)) * scale);
            }
            if (hp <= 1) {
                wear = 1; // vanilla break
            }
            int durability = tool.getMaxDurability();
            double ticksPerHit = ticksToBreak(data, block, new ItemStack(tool)) + DESTROY_DELAY;
            cell.put("tool", tool.name().toLowerCase(Locale.ROOT));
            cell.put("hits", hits);
            cell.put("seconds", Math.round(hits * ticksPerHit / 20.0 * 10) / 10.0);
            cell.put("wear", wear);
            cell.put("toolsPerBlock", durability <= 0 ? 0 : Math.round(wear / (double) durability * 1000) / 1000.0);
            cell.put("blocksPerTool", durability <= 0 ? 0 : Math.round(durability / (double) wear * 10) / 10.0);
            cells.add(cell);
        }
        return cells;
    }

    /** Tool of this tier suited to the block: pickaxe, axe or shovel (pickaxe by default). */
    private static Material toolFor(BlockData data, Tier t) {
        Material fallback = null;
        for (String kind : List.of("PICKAXE", "AXE", "SHOVEL")) {
            Material tool = Material.matchMaterial(t.prefix() + "_" + kind);
            if (tool == null) {
                continue;
            }
            if (fallback == null) {
                fallback = tool;
            }
            if (data.isPreferredTool(new ItemStack(tool))) {
                return tool;
            }
        }
        return fallback;
    }

    /** Vanilla time to break the block with the tool, in ticks. */
    private static double ticksToBreak(BlockData data, Material block, ItemStack tool) {
        float hardness = block.asBlockType().getHardness();
        if (hardness <= 0) {
            return 0;
        }
        float speed = data.getDestroySpeed(tool, false);
        boolean harvest = !data.requiresCorrectToolForDrops() || data.isPreferredTool(tool);
        double perTick = speed / hardness / (harvest ? 30.0 : 100.0);
        return perTick >= 1 ? 0 : Math.ceil(1 / perTick);
    }

    private static String name(Material m) {
        return m.name().toLowerCase(Locale.ROOT);
    }

    // --- Energy ---

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** What energy can grow: from a full chunk and per hour of regeneration. */
    private static Map<String, Object> growth(String what, double cost, double stock, double perHour) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("what", what);
        m.put("cost", r1(cost));
        m.put("fromFull", cost <= 0 ? null : r1(stock / cost));
        m.put("perHour", cost <= 0 ? null : r1(perHour / cost));
        m.put("perDay", cost <= 0 ? null : r1(perHour * 24 / cost));
        return m;
    }

    private Map<String, Object> energyStats(HeartholdConfig c) {
        HeartholdConfig.EnergySettings e = c.energy();
        Map<String, Object> m = new LinkedHashMap<>();
        // Chunk.
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("naturalMax", e.chunkNaturalMax());
        chunk.put("regenPerHour", e.chunkRegenPerHour());
        chunk.put("cap", e.chunkMax());
        chunk.put("hoursToFill", e.chunkRegenPerHour() <= 0 ? null : r1(e.chunkNaturalMax() / e.chunkRegenPerHour()));
        m.put("chunk", chunk);
        m.put("costs", Map.of("cropStage", e.costCropStage(), "tree", e.costTree(), "bonemeal", e.costBonemeal(),
                "breed", e.costBreed()));
        // Growth in an ordinary chunk (stages: wheat, carrots, potatoes — 7; beetroot — 3).
        List<Object> grow = new ArrayList<>();
        double stock = e.chunkNaturalMax();
        double regen = e.chunkRegenPerHour();
        grow.add(growth(Lang.tr("web.admin.growth.crops"), e.costCropStage() * 7, stock, regen));
        grow.add(growth(Lang.tr("web.admin.growth.beetroot"), e.costCropStage() * 3, stock, regen));
        grow.add(growth(Lang.tr("web.admin.growth.stage"), e.costCropStage(), stock, regen));
        grow.add(growth(Lang.tr("web.admin.growth.tree"), e.costTree(), stock, regen));
        grow.add(growth(Lang.tr("web.admin.growth.bonemeal"), e.costBonemeal(), stock, regen));
        grow.add(growth(Lang.tr("web.admin.growth.breedPair"), e.costBreed(), stock, regen));
        m.put("growth", grow);
        // From a full chunk filled with cells up to the cap.
        m.put("growthAtCap", List.of(
                growth(Lang.tr("web.admin.growth.wheat"), e.costCropStage() * 7, e.chunkMax(), 0),
                growth(Lang.tr("web.admin.growth.breed"), e.costBreed(), e.chunkMax(), 0)));
        List<Object> fertile = new ArrayList<>();
        for (HeartholdConfig.FertileZone f : e.fertileZones()) {
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("name", f.area().name());
            z.put("multiplier", f.multiplier());
            z.put("naturalMax", r1(e.chunkNaturalMax() * f.multiplier()));
            z.put("regenPerHour", r1(e.chunkRegenPerHour() * f.multiplier()));
            z.put("wheatPerHour", e.costCropStage() <= 0 ? null
                    : r1(e.chunkRegenPerHour() * f.multiplier() / (e.costCropStage() * 7)));
            fertile.add(z);
        }
        m.put("fertile", fertile);
        // Clan energy: example clan compositions.
        List<Object> clanRows = new ArrayList<>();
        int[][] examples = {{1, 0}, {3, 0}, {3, 2}, {6, 4}, {10, 4}};
        for (int[] ex : examples) {
            double perHour = e.heartBasePerHour() + e.perMemberPerHour() * ex[0] + e.perNodePerHour() * ex[1];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("members", ex[0]);
            row.put("nodes", ex[1]);
            row.put("perHour", r1(perHour));
            row.put("perDay", r1(perHour * 24));
            row.put("hoursToMax", perHour <= 0 ? null : r1(e.heartMax() / perHour));
            row.put("cellsPerDay", e.cellSize() <= 0 ? null : r1(perHour * 24 / e.cellSize()));
            row.put("wheatPerDay", e.costCropStage() <= 0 ? null : r1(perHour * 24 / (e.costCropStage() * 7)));
            row.put("chunksFilled", e.chunkMax() <= e.chunkNaturalMax() ? null
                    : r1(perHour * 24 / (e.chunkMax() - e.chunkNaturalMax())));
            clanRows.add(row);
        }
        m.put("clanExamples", clanRows);
        m.put("heartMax", e.heartMax());
        m.put("cellSize", e.cellSize());
        m.put("perPrestige", c.plot().energyPerPrestige());
        m.put("perMember", e.perMemberPerHour());
        m.put("perNode", e.perNodePerHour());
        m.put("heartBase", e.heartBasePerHour());
        return m;
    }

    // --- Collectors ---

    private Map<String, Object> collectorStats(HeartholdConfig c) {
        var s = c.map().collector();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxPerClan", s.maxPerClan());
        m.put("maxItems", s.maxItems());
        m.put("intervalMinutes", s.intervalMinutes());
        List<Object> rows = new ArrayList<>();
        for (int n = 1; n <= Math.max(1, s.maxPerClan()); n++) {
            double eff = s.efficiencyFor(n);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("count", n);
            row.put("eachPercent", Math.round(eff * 100));
            row.put("penaltyPercent", Math.round((1 - eff) * 100));
            row.put("totalVsOne", r1(n * eff));
            rows.add(row);
        }
        m.put("penalty", rows);
        return m;
    }

    // --- Clans ---

    private List<Object> clanTable() {
        long now = System.currentTimeMillis();
        List<Object> rows = new ArrayList<>();
        for (Clan clan : clans.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", clan.name());
            m.put("members", clan.members().size());
            m.put("level", levels == null ? "I" : levels.of(clan.id()).name());
            double emeralds = economy.emeralds(clan.id());
            double cost = economy.dailyCost(clan.id());
            m.put("emeralds", Math.round(emeralds * 10) / 10.0);
            m.put("dailyCost", Math.round(cost * 10) / 10.0);
            m.put("daysLeft", cost > 0 ? Math.round(emeralds / cost * 10) / 10.0 : null);
            m.put("protected", economy.isProtected(clan.id()));
            m.put("energy", Math.round(economy.energy(clan.id())));
            m.put("fatigue", Math.round(wars.fatigue(clan.id()) * 100) / 100.0);
            m.put("upkeepMultiplier", Math.round(wars.upkeepMultiplier(clan.id()) * 100) / 100.0);
            m.put("shielded", wars.isShielded(clan.id()));
            m.put("status", baseNotice.apply(clan.id()));
            m.put("reputation", trade.reputation(clan.id()));
            Map<String, Object> goods = new LinkedHashMap<>();
            treasury.contents(clan.id()).forEach((mat, n) -> goods.put(name(mat), n));
            m.put("tradeTreasury", goods);
            List<Object> routes = new ArrayList<>();
            for (TradeRoute r : trade.routesOf(clan.id())) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", r.shortId());
                rm.put("partner", clans.byId(r.partnerOf(clan.id())).map(Clan::name).orElse("?"));
                rm.put("periodDays", r.periodDays());
                rm.put("status", r.status() == TradeRoute.Status.ACTIVE ? Lang.tr("web.route.active")
                        : r.status().name().toLowerCase(Locale.ROOT));
                rm.put("gives", r.lines().stream().filter(l -> l.fromClan().equals(clan.id()))
                        .map(l -> l.amount() + " " + name(l.material())).toList());
                rm.put("takes", r.lines().stream().filter(l -> !l.fromClan().equals(clan.id()))
                        .map(l -> l.amount() + " " + name(l.material())).toList());
                routes.add(rm);
            }
            m.put("routes", routes);
            List<Object> warList = new ArrayList<>();
            for (War w : wars.ongoing()) {
                if (!w.attacker().equals(clan.id()) && !w.defender().equals(clan.id())) {
                    continue;
                }
                Map<String, Object> wm = new LinkedHashMap<>();
                boolean attacking = w.attacker().equals(clan.id());
                wm.put("role", Lang.tr(attacking ? "web.admin.war.attacking" : "web.admin.war.defending"));
                wm.put("against", attacking ? w.defenderName() : w.attackerName());
                wm.put("active", w.isActive(now));
                wm.put("endsAt", w.endsAt());
                warList.add(wm);
            }
            m.put("wars", warList);
            rows.add(m);
        }
        return rows;
    }
}
