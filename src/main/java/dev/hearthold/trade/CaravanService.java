package dev.hearthold.trade;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.weight.WeightService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Equipping, delivering, cancelling and looting caravans. */
public final class CaravanService {

    private final ClanService clans;
    private final TradeService trade;
    private final Treasury treasury;
    private final Suitcase suitcase;
    private final CaravanLog log;
    private final WeightService weights;
    private final Supplier<HeartholdConfig> config;
    /** Vassal tribute from trade routes: (clan, equipped cargo). */
    private java.util.function.BiConsumer<UUID, Map<Material, Integer>> tradeTribute = (c, m) -> { };
    /** Tribute treasury and an "is this a vassalage id" check, for cancelling a tribute caravan. */
    private Treasury tributeTreasury;
    private java.util.function.Predicate<UUID> isVassalage = id -> false;

    public void setTribute(java.util.function.BiConsumer<UUID, Map<Material, Integer>> tradeTribute,
                           Treasury tributeTreasury, java.util.function.Predicate<UUID> isVassalage) {
        this.tradeTribute = tradeTribute;
        this.tributeTreasury = tributeTreasury;
        this.isVassalage = isVassalage;
    }

    public CaravanService(ClanService clans, TradeService trade, Treasury treasury, Suitcase suitcase,
                          CaravanLog log, WeightService weights, Supplier<HeartholdConfig> config) {
        this.clans = clans;
        this.trade = trade;
        this.treasury = treasury;
        this.suitcase = suitcase;
        this.log = log;
        this.weights = weights;
        this.config = config;
    }

    /** Cargo weight in a suitcase (for the weight system). */
    public double cargoWeight(ItemStack item) {
        return suitcase.read(item)
                .map(d -> d.cargo().entrySet().stream().mapToDouble(e -> weights.weight(e.getKey(), e.getValue())).sum())
                .orElse(0.0);
    }

    /**
     * Equips a caravan: takes as much route cargo from the treasury as the weight allows
     * (no overload; when mounted, the mount's capacity counts), but no more than what is still owed.
     */
    public Map<Material, Integer> equip(Player player, Clan clan, TradeRoute route) throws ClanException {
        if (route.status() != TradeRoute.Status.ACTIVE) {
            throw new ClanException(Lang.tr("trade.equip.terminated"));
        }
        Map<Material, Integer> remaining = route.remaining(clan.id());
        if (remaining.values().stream().allMatch(v -> v <= 0)) {
            throw new ClanException(Lang.tr("trade.equip.nothing-owed"));
        }
        UUID to = route.partnerOf(clan.id());
        Map<Material, Integer> cargo = pack(player, treasury, clan.id(), remaining, route.id(), route.shortId(),
                clan.id(), clan.name(), to);
        // A vassal pays a share of its trade route cargo into the tribute treasury.
        tradeTribute.accept(clan.id(), cargo);
        return cargo;
    }

    /**
     * Tribute caravan: the suzerain's courier collects the tribute treasury in the vassal's base. The suitcase
     * sender is the vassal (its members cannot open the cargo), the recipient is the suzerain.
     */
    public Map<Material, Integer> equipTribute(Player player, UUID vassalageId, String shortId, UUID vassal,
                                               String vassalName, UUID suzerain) throws ClanException {
        Map<Material, Integer> all = tributeTreasury.contents(vassalageId);
        if (all.isEmpty()) {
            throw new ClanException(Lang.tr("trade.tribute.empty"));
        }
        return pack(player, tributeTreasury, vassalageId, all, vassalageId, shortId, vassal, vassalName, suzerain);
    }

    private Map<Material, Integer> pack(Player player, Treasury source, UUID sourceKey, Map<Material, Integer> wanted,
                                        UUID routeId, String routeShort, UUID from, String fromName, UUID to)
            throws ClanException {
        var w = config.get().weight();
        double capacity = weights.capacity(player);
        double load = weights.weight(player);
        Entity vehicle = player.getVehicle();
        if (vehicle != null && w.mountCapacity().containsKey(vehicle.getType())) {
            capacity += w.mountCapacity().get(vehicle.getType());
            load += weights.cargo(vehicle);
        }
        double budget = w.enabled() ? capacity * w.slowTier() - load : Double.MAX_VALUE;

        Map<Material, Integer> cargo = new LinkedHashMap<>();
        for (var e : wanted.entrySet()) {
            int want = Math.min(e.getValue(), source.amount(sourceKey, e.getKey()));
            double unit = weights.weight(e.getKey(), 1);
            if (unit > 0) {
                want = (int) Math.min(want, Math.floor(budget / unit));
            }
            if (want <= 0) {
                continue;
            }
            int taken = source.take(sourceKey, e.getKey(), want);
            if (taken > 0) {
                cargo.put(e.getKey(), taken);
                budget -= unit * taken;
            }
        }
        if (cargo.isEmpty()) {
            throw new ClanException(Lang.tr("trade.equip.nothing"));
        }
        String toName = clans.byId(to).map(Clan::name).orElse("?");
        Suitcase.Data data = new Suitcase.Data(UUID.randomUUID(), routeId, from, to, player.getName(), cargo, false);
        ItemStack item = suitcase.create(data, fromName, toName, routeShort);
        player.getInventory().addItem(item).values()
                .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        log.add(clans.ofPlayer(player.getUniqueId()).map(Clan::id).orElse(from), player.getName(), Lang.tr("trade.log.equipped"),
                Lang.tr("trade.log.equipped-details", routeShort, fromName, toName, describe(cargo)));
        return cargo;
    }

    /** Delivery into the recipient's treasury, credited against the route. */
    public void deliver(Suitcase.Data d, String byPlayer) {
        d.cargo().forEach((m, a) -> treasury.add(d.toClan(), m, a));
        Optional<TradeRoute> route = trade.route(d.routeId());
        route.ifPresent(r -> d.cargo().forEach((m, a) -> trade.deliver(r, d.fromClan(), m, a)));
        String cargo = describe(d.cargo());
        String fromName = clans.byId(d.fromClan()).map(Clan::name).orElse("?");
        String toName = clans.byId(d.toClan()).map(Clan::name).orElse("?");
        log.add(d.fromClan(), byPlayer, Lang.tr("trade.log.delivered"), toName + ": " + cargo);
        log.add(d.toClan(), byPlayer, Lang.tr("trade.log.received"), Lang.tr("trade.log.received-details", fromName, cargo));
        Component msg = Component.text(Lang.tr("trade.caravan.delivered", fromName, toName, cargo),
                NamedTextColor.GREEN);
        clans.byId(d.fromClan()).ifPresent(c -> ClanService.broadcast(c, msg));
        clans.byId(d.toClan()).ifPresent(c -> ClanService.broadcast(c, msg));
    }

    /** Cancellation in the own base: cargo returns to the sender's treasury. */
    public void cancel(Suitcase.Data d, String byPlayer) {
        if (isVassalage.test(d.routeId()) && tributeTreasury != null) {
            d.cargo().forEach((m, a) -> tributeTreasury.add(d.routeId(), m, a));
            log.add(d.toClan(), byPlayer, Lang.tr("trade.log.tribute-cancelled"), Lang.tr("trade.log.tribute-cancelled-details", describe(d.cargo())));
            return;
        }
        d.cargo().forEach((m, a) -> treasury.add(d.fromClan(), m, a));
        log.add(d.fromClan(), byPlayer, Lang.tr("trade.log.cancelled"), Lang.tr("trade.log.cancelled-details", describe(d.cargo())));
    }

    /** A suitcase opened by an outsider: the cargo goes to them. */
    public void loot(Suitcase.Data d, Player player) {
        d.cargo().forEach((m, a) -> {
            int left = a;
            while (left > 0) {
                int n = Math.min(left, m.getMaxStackSize());
                player.getInventory().addItem(new ItemStack(m, n)).values()
                        .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
                left -= n;
            }
        });
        String who = player.getName() + clans.ofPlayer(player.getUniqueId()).map(c -> " (" + c.name() + ")").orElse("");
        log.add(d.fromClan(), who, Lang.tr("trade.log.looted"), describe(d.cargo()));
        clans.byId(d.fromClan()).ifPresent(c -> ClanService.broadcast(c, Component.text(
                Lang.tr("trade.caravan.looted", d.courier(), who), NamedTextColor.RED)));
    }

    public static String describe(Map<Material, Integer> cargo) {
        return cargo.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase())
                .collect(Collectors.joining(", "));
    }
}
