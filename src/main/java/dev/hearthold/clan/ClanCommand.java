package dev.hearthold.clan;

import dev.hearthold.i18n.Lang;
import dev.hearthold.base.BaseItems;
import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.collector.CollectorListener;
import dev.hearthold.config.MapConfig;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.economy.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** /clan create|invite|accept|leave|info|heart|node|ally|unally */
public final class ClanCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("create", "invite", "accept", "leave", "info", "heart", "node", "collector", "proxy", "ally", "unally", "trust", "untrust");

    private final ClanService clans;
    private final TerritoryService territory;
    private final AllyService allies;
    private final EconomyService economy;
    private final BaseItems items;
    private final CollectorListener collectorItems;
    private final Supplier<HeartholdConfig> config;
    private dev.hearthold.newbie.NewbieService newbie;

    private dev.hearthold.collector.CollectorService collectorService;

    public void setCollectors(dev.hearthold.collector.CollectorService collectorService) {
        this.collectorService = collectorService;
    }

    public void setNewbie(dev.hearthold.newbie.NewbieService newbie) {
        this.newbie = newbie;
    }

    public ClanCommand(ClanService clans, AllyService allies, TerritoryService territory, EconomyService economy,
                       BaseItems items, CollectorListener collectorItems, Supplier<HeartholdConfig> config) {
        this.collectorItems = collectorItems;
        this.economy = economy;
        this.clans = clans;
        this.allies = allies;
        this.territory = territory;
        this.items = items;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("clan.players-only"));
            return true;
        }
        if (args.length == 0) {
            usage(player);
            return true;
        }
        try {
            switch (args[0].toLowerCase()) {
                case "create" -> create(player, args);
                case "invite" -> invite(player, args);
                case "accept" -> accept(player);
                case "leave" -> leave(player);
                case "info" -> info(player, args);
                case "heart" -> heart(player);
                case "node" -> node(player, args);
                case "collector" -> collector(player, args);
                case "proxy" -> proxy(player);
                case "ally" -> ally(player, args);
                case "unally" -> unally(player, args);
                case "trust" -> trust(player, args, true);
                case "untrust" -> trust(player, args, false);
                default -> usage(player);
            }
        } catch (ClanException e) {
            player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private void create(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("clan.usage.create"));
        }
        if (args[1].toLowerCase(java.util.Locale.ROOT).startsWith(Clan.DUMMY_PREFIX.toLowerCase(java.util.Locale.ROOT))) {
            throw new ClanException(Lang.tr("clan.name.reserved", Clan.DUMMY_PREFIX));
        }
        Clan clan = clans.create(args[1], player.getUniqueId());
        ok(player, Lang.tr("clan.created", clan.name()));
    }

    private void invite(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("clan.usage.invite"));
        }
        Clan clan = requireClan(player);
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            throw new ClanException(Lang.tr("clan.player.offline"));
        }
        boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        if (newbie != null && newbie.isProtected(clan.id()) && newbie.isExperienced(target.getUniqueId()) && !confirmed) {
            player.sendMessage(Component.text(Lang.tr("clan.invite.experienced-warning") + " ", NamedTextColor.GOLD)
                    .append(Component.text(Lang.tr("clan.invite.experienced-confirm"), NamedTextColor.RED)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                    "/clan invite " + target.getName() + " confirm"))));
            return;
        }
        clans.invite(clan, player.getUniqueId(), target.getUniqueId());
        ok(player, Lang.tr("clan.invite.sent", target.getName()));
        target.sendMessage(Component.text(Lang.tr("clan.invite.received", clan.name()) + " ", NamedTextColor.YELLOW)
                .append(Component.text(Lang.tr("clan.invite.accept-button"), NamedTextColor.GREEN)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/clan accept"))));
    }

    private void accept(Player player) throws ClanException {
        Clan clan = clans.accept(player.getUniqueId());
        ok(player, Lang.tr("clan.joined", clan.name()));
        if (newbie != null) {
            newbie.onJoin(clan, player.getUniqueId());
        }
    }

    private void leave(Player player) throws ClanException {
        Clan clan = requireClan(player);
        clans.leave(player.getUniqueId());
        ok(player, Lang.tr("clan.left", clan.name()));
    }

    private void info(Player player, String[] args) throws ClanException {
        Clan clan = args.length >= 2
                ? clans.byName(args[1]).orElseThrow(() -> new ClanException(Lang.tr("clan.not-found")))
                : requireClan(player);
        String members = clan.members().entrySet().stream()
                .map(e -> name(e.getKey()) + switch (e.getValue()) {
                    case LEADER -> " " + Lang.tr("clan.info.leader-suffix");
                    case TRUSTED -> " " + Lang.tr("clan.info.trusted-suffix");
                    case MEMBER -> "";
                })
                .collect(Collectors.joining(", "));
        player.sendMessage(Component.text(Lang.tr("clan.info.title", clan.name()), NamedTextColor.GOLD));
        player.sendMessage(Component.text(Lang.tr("clan.info.members", members), NamedTextColor.GRAY));
        long now = System.currentTimeMillis();
        List<Sphere> spheres = territory.spheresOf(clan.id());
        if (spheres.isEmpty()) {
            player.sendMessage(Component.text(Lang.tr("clan.info.no-heart"), NamedTextColor.GRAY));
        }
        for (Sphere s : spheres) {
            String state = s.isActive(now) ? "" : " — " + Lang.tr("clan.info.activates-in", minutesLeft(s, now));
            player.sendMessage(Component.text(s.describe() + state, NamedTextColor.GRAY));
        }
        String allyNames = allies.alliesOf(clan.id()).stream()
                .map(id -> clans.byId(id).map(Clan::name).orElse("?"))
                .collect(Collectors.joining(", "));
        player.sendMessage(Component.text(Lang.tr("clan.info.allies", allyNames.isEmpty() ? Lang.tr("clan.info.none") : allyNames), NamedTextColor.GRAY));
        if (newbie != null && newbie.isProtected(clan.id())) {
            player.sendMessage(Component.text(Lang.tr("clan.info.newbie-until", java.time.format.DateTimeFormatter.ofPattern("d.MM HH:mm")
                    .format(java.time.Instant.ofEpochMilli(newbie.until(clan.id())).atZone(java.time.ZoneId.systemDefault()))),
                    NamedTextColor.AQUA));
        }
        if (clan.isMember(player.getUniqueId()) && !spheres.isEmpty()) {
            double cost = economy.dailyCost(clan.id());
            double emeralds = economy.emeralds(clan.id());
            String days = cost > 0 ? " " + Lang.tr("clan.info.upkeep-days",
                    String.format(java.util.Locale.ROOT, "%.1f", emeralds / cost)) : "";
            player.sendMessage(Component.text(Lang.tr("clan.info.upkeep",
                    String.format(java.util.Locale.ROOT, "%.1f", emeralds),
                    String.format(java.util.Locale.ROOT, "%.1f", cost), days,
                    String.format(java.util.Locale.ROOT, "%.0f", economy.energy(clan.id())),
                    economy.isProtected(clan.id()) ? Lang.tr("clan.info.protected-yes") : Lang.tr("clan.info.protected-no")),
                    NamedTextColor.GRAY));
        }
    }

    private void heart(Player player) throws ClanException {
        Clan clan = requireClan(player);
        if (!clan.isLeader(player.getUniqueId())) {
            throw new ClanException(Lang.tr("clan.heart.leader-only"));
        }
        if (territory.heartOf(clan.id()).isPresent()) {
            throw new ClanException(Lang.tr("clan.heart.already"));
        }
        var n = config.get().newbie();
        boolean first = newbie != null && newbie.eligible(clan);
        double cost = first ? n.firstHeartCost() : n.heartCost();
        pay(player, cost, first ? Lang.tr("clan.pay.first-base") : Lang.tr("clan.pay.heart"));
        give(player, Sphere.Kind.HEART);
        ok(player, Lang.tr("clan.heart.received")
                + (first ? " " + Lang.tr("clan.heart.first-base") : ""));
    }

    /** A proxy is a separate expensive outpost base, vulnerable right away. */
    private void proxy(Player player) throws ClanException {
        Clan clan = requireLeader(player);
        var n = config.get().newbie();
        if (territory.heartOf(clan.id()).isEmpty()) {
            throw new ClanException(Lang.tr("clan.heart.place-first"));
        }
        if (territory.proxyCount(clan.id()) >= n.maxProxies()) {
            throw new ClanException(Lang.tr("clan.proxy.max", n.maxProxies()));
        }
        pay(player, n.proxyCost(), Lang.tr("clan.pay.proxy"));
        give(player, Sphere.Kind.PROXY);
        ok(player, Lang.tr("clan.proxy.received", String.format(java.util.Locale.ROOT, "%.0f", n.proxyPerDay())));
        if (newbie != null && newbie.isProtected(clan.id())) {
            player.sendMessage(Component.text(Lang.tr("clan.proxy.newbie-warning"), NamedTextColor.GOLD));
        }
    }

    /** Takes emeralds from the inventory. */
    private static void pay(Player player, double cost, String what) throws ClanException {
        int need = (int) Math.ceil(cost);
        if (need <= 0) {
            return;
        }
        if (!player.getInventory().containsAtLeast(new org.bukkit.inventory.ItemStack(Material.EMERALD), need)) {
            throw new ClanException(Lang.tr("clan.pay.need", need, what));
        }
        player.getInventory().removeItem(new org.bukkit.inventory.ItemStack(Material.EMERALD, need));
    }

    /** /clan node buys a node; /clan node remove deletes the node the leader is looking at (no refund). */
    private void node(Player player, String[] args) throws ClanException {
        Clan clan = requireLeader(player);
        if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
            var target = player.getTargetBlockExact(6);
            Sphere node = target == null ? null : territory.sphereAt(target).orElse(null);
            if (node == null || node.kind() != Sphere.Kind.NODE || !node.clanId().equals(clan.id())) {
                throw new ClanException(Lang.tr("clan.node.look-at"));
            }
            String denial = territory.removalDenial(node);
            if (denial != null) {
                throw new ClanException(denial);
            }
            territory.removeNode(node);
            target.setType(Material.AIR);
            ok(player, Lang.tr("clan.node.removed"));
            return;
        }
        if (territory.heartOf(clan.id()).isEmpty()) {
            throw new ClanException(Lang.tr("clan.heart.place-first"));
        }
        if (newbie != null && newbie.isProtected(clan.id())) {
            throw new ClanException(Lang.tr("clan.node.newbie"));
        }
        int max = config.get().maxNodes();
        if (territory.nodeCount(clan.id()) >= max) {
            throw new ClanException(Lang.tr("clan.node.max", max));
        }
        pay(player, config.get().nodeCost(), Lang.tr("clan.pay.node"));
        give(player, Sphere.Kind.NODE);
        ok(player, Lang.tr("clan.node.received"));
    }

    /** /clan collector gives the leader a collector and the blueprint; /clan collector help gives only the blueprint, to anyone. */
    private void collector(Player player, String[] args) throws ClanException {
        if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
            collectorItems.guide().sendInstructions(player);
            return;
        }
        Clan clan = requireLeader(player);
        double cost = 0;
        if (collectorService != null) {
            int max = config.get().map().collector().maxPerClan();
            if (collectorService.countOf(clan.id()) >= max) {
                throw new ClanException(Lang.tr("clan.collector.max", max));
            }
            cost = collectorService.nextCost(clan.id());
            pay(player, cost, Lang.tr("clan.pay.collector"));
            collectorService.markIssued(clan.id());
        }
        var leftover = player.getInventory().addItem(collectorItems.createItem());
        leftover.values().forEach(item -> player.getWorld().dropItem(player.getLocation(), item));
        ok(player, cost <= 0 ? Lang.tr("clan.collector.received-free")
                : Lang.tr("clan.collector.received", String.format(java.util.Locale.ROOT, "%.0f", cost)));
        collectorItems.guide().sendInstructions(player);
    }

    private void ally(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("clan.usage.ally"));
        }
        Clan clan = requireLeader(player);
        Clan other = clans.byName(args[1]).orElseThrow(() -> new ClanException(Lang.tr("clan.not-found")));
        if (allies.propose(clan.id(), other.id())) {
            Component msg = Component.text(Lang.tr("clan.ally.formed", clan.name(), other.name()),
                    NamedTextColor.GREEN);
            ClanService.broadcast(clan, msg);
            ClanService.broadcast(other, msg);
        } else {
            ok(player, Lang.tr("clan.ally.proposed", other.name()));
            ClanService.broadcast(other, Component.text(Lang.tr("clan.ally.incoming", clan.name()), NamedTextColor.YELLOW));
        }
    }

    private void unally(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("clan.usage.unally"));
        }
        Clan clan = requireLeader(player);
        Clan other = clans.byName(args[1]).orElseThrow(() -> new ClanException(Lang.tr("clan.not-found")));
        allies.breakAlliance(clan.id(), other.id());
        Component msg = Component.text(Lang.tr("clan.ally.broken", clan.name(), other.name()),
                NamedTextColor.GOLD);
        ClanService.broadcast(clan, msg);
        ClanService.broadcast(other, msg);
    }

    /** A trusted member manages the treasury and equips caravans. */
    private void trust(Player player, String[] args, boolean trusted) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("clan.usage.trust", trusted ? "trust" : "untrust"));
        }
        Clan clan = requireLeader(player);
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            throw new ClanException(Lang.tr("clan.player.not-found"));
        }
        clans.setTrusted(clan, target.getUniqueId(), trusted);
        ok(player, trusted ? Lang.tr("clan.trusted.granted", args[1]) : Lang.tr("clan.trusted.revoked", args[1]));
    }

    private void give(Player player, Sphere.Kind kind) {
        var leftover = player.getInventory().addItem(items.create(kind, config.get().sphere(kind).material()));
        leftover.values().forEach(item -> player.getWorld().dropItem(player.getLocation(), item));
    }

    private Clan requireLeader(Player player) throws ClanException {
        Clan clan = requireClan(player);
        if (!clan.isLeader(player.getUniqueId())) {
            throw new ClanException(Lang.tr("clan.leader-only"));
        }
        return clan;
    }

    private static long minutesLeft(Sphere s, long now) {
        return Math.max(1, (s.activeAt() - now + 59_999) / 60_000);
    }

    private Clan requireClan(Player player) throws ClanException {
        return clans.ofPlayer(player.getUniqueId()).orElseThrow(() -> new ClanException(Lang.tr("clan.not-in-clan")));
    }

    private static String name(UUID id) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        return Optional.ofNullable(p.getName()).orElse(id.toString().substring(0, 8));
    }

    private static void ok(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void usage(Player player) {
        player.sendMessage(Component.text(Lang.tr("clan.usage"),
                NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && List.of("invite", "trust", "untrust").contains(args[0].toLowerCase())) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("node")) {
            return "remove".startsWith(args[1].toLowerCase()) ? List.of("remove") : List.of();
        }
        if (args.length == 2 && List.of("info", "ally", "unally").contains(args[0].toLowerCase())) {
            return clans.all().stream().map(Clan::name)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
