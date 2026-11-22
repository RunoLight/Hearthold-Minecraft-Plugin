package dev.hearthold.ui;

import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.collector.CollectorGuide;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.economy.EconomyService;
import dev.hearthold.economy.EnergyCell;
import dev.hearthold.i18n.Lang;
import dev.hearthold.newbie.NewbieService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base heart menu (right-click the heart or open from the compass): a chat-free summary, the treasury chest,
 * the energy cell, members with roles and tags, inviting players from those online.
 */
public final class HeartMenu {

    private final ClanService clans;
    private final TerritoryService territory;
    private final EconomyService economy;
    private final NewbieService newbie;
    private final HeartTreasury treasury;
    private final EnergyCell cells;
    private final CollectorGuide collectorGuide;
    private final Supplier<HeartholdConfig> config;

    public HeartMenu(ClanService clans, TerritoryService territory, EconomyService economy, NewbieService newbie,
                     HeartTreasury treasury, EnergyCell cells, CollectorGuide collectorGuide,
                     Supplier<HeartholdConfig> config) {
        this.clans = clans;
        this.territory = territory;
        this.economy = economy;
        this.newbie = newbie;
        this.treasury = treasury;
        this.cells = cells;
        this.collectorGuide = collectorGuide;
        this.config = config;
    }

    private CollectorsMenu collectorsMenu;
    private ActionsMenu actions;

    public void setActions(ActionsMenu actions) {
        this.actions = actions;
    }
    private dev.hearthold.base.BaseLevelService levels;

    public void setLevels(dev.hearthold.base.BaseLevelService levels) {
        this.levels = levels;
    }

    private dev.hearthold.base.HeartRemovalService removal;

    public void setRemoval(dev.hearthold.base.HeartRemovalService removal) {
        this.removal = removal;
    }

    public void setCollectorsMenu(CollectorsMenu collectorsMenu) {
        this.collectorsMenu = collectorsMenu;
    }

    /** Players who opened the menu by clicking the heart (only they can use the treasury). */
    private final java.util.Set<UUID> atHeart = new java.util.HashSet<>();

    /** Opens the player's own clan menu remotely (from the compass); false if the player has no clan. */
    public boolean openOwn(Player player) {
        Optional<Clan> clan = clans.ofPlayer(player.getUniqueId());
        atHeart.remove(player.getUniqueId());
        clan.ifPresent(c -> open(player, c));
        return clan.isPresent();
    }

    private static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    public void open(Player player, Clan clan) {
        if (!clan.isMember(player.getUniqueId())) {
            return;
        }
        UUID me = player.getUniqueId();
        Menu m = new Menu(6, Component.text(Lang.tr("heart.menu.title", clan.name())));
        m.set(4, Menu.icon(Material.BEACON, Sphere.Kind.HEART.title(), summary(clan)), p -> open(p, clan));

        // Row 2: actions.
        boolean treasurer = clan.hasAccess(me, Clan.Tag.TREASURER);
        boolean nearHeart = atHeart.contains(me);
        List<String> treasuryLore = !treasurer ? List.of(Lang.tr("heart.treasury.lore.no-access"))
                : !nearHeart ? List.of(Lang.tr("heart.treasury.lore.far1"), Lang.tr("heart.treasury.lore.far2"),
                        Lang.tr("heart.treasury.lore.far3"))
                : List.of(Lang.tr("heart.treasury.lore.open1"), Lang.tr("heart.treasury.lore.open2"),
                        Lang.tr("heart.treasury.lore.open3"), Lang.tr("heart.treasury.lore.open4"));
        m.set(10, Menu.icon(Material.EMERALD_BLOCK, Lang.tr("heart.treasury.name"), treasuryLore), p -> {
            if (!atHeart.contains(p.getUniqueId())) {
                p.sendActionBar(Component.text(Lang.tr("heart.treasury.only-at-heart"), NamedTextColor.RED));
                return;
            }
            p.closeInventory();
            treasury.open(p, clan);
        });
        m.set(11, Menu.icon(Material.GILDED_BLACKSTONE, Lang.tr("heart.spheres.name"),
                Lang.tr("heart.spheres.nodes", territory.nodeCount(clan.id()), config.get().maxNodes()),
                Lang.tr("heart.spheres.proxies", territory.proxyCount(clan.id()), config.get().newbie().maxProxies()),
                Lang.tr("heart.spheres.hint")), p -> spheresMenu(p, clan));
        HeartholdConfig.EnergySettings e = config.get().energy();
        m.set(12, Menu.icon(e.cellMaterial(), Lang.tr("heart.cell.name"),
                Lang.tr("heart.cell.stored", f0(economy.energy(clan.id())), f0(e.heartMax())),
                Lang.tr("heart.cell.rate", f0(economy.energyPerHour(clan.id()))),
                Lang.tr("heart.cell.size", f0(e.cellSize())),
                Lang.tr("heart.cell.hint")), p -> takeCell(p, clan));
        if (actions != null) {
            m.set(9, Menu.icon(Material.CYAN_BANNER, Lang.tr("heart.manage.name"), Lang.tr("heart.manage.lore1"),
                    Lang.tr("heart.manage.lore2")), p -> actions.openManage(p, q -> open(q, clan)));
        }
        m.set(14, Menu.icon(Material.WRITABLE_BOOK, Lang.tr("heart.invite.name"),
                clan.isLeader(me) ? Lang.tr("heart.invite.lore.leader") : Lang.tr("heart.invite.lore.other")), p -> {
            if (clan.isLeader(p.getUniqueId())) {
                invitePicker(p, clan);
            }
        });
        if (levels != null) {
            m.set(13, levelIcon(clan, me), p -> {
                if (clan.isLeader(p.getUniqueId()) && levels.next(clan.id()).isPresent()) {
                    confirmUpgrade(p, clan);
                }
            });
        }
        if (collectorsMenu != null) {
            m.set(15, Menu.icon(Material.HOPPER, Lang.tr("heart.collectors.name"), Lang.tr("heart.collectors.lore")),
                    p -> collectorsMenu.open(p, q -> open(q, clan)));
        }
        m.set(16, Menu.icon(Material.BARREL, Lang.tr("heart.blueprint.name"), Lang.tr("heart.blueprint.lore")), p -> {
            p.closeInventory();
            collectorGuide.sendInstructions(p);
        });

        if (removal != null) {
            removalButton(m, 8, clan, clan, p -> open(p, clan));
        }

        // Rows 3–6: members. The leader assigns tags by clicking a member's head.
        List<String> tagLore = new ArrayList<>();
        tagLore.add(Lang.tr("heart.tags.intro"));
        for (Clan.Tag tag : Clan.Tag.values()) {
            tagLore.add(Lang.tr("heart.tags.line", tag.title(), tag.description()));
        }
        tagLore.add(Lang.tr("heart.tags.trusted"));
        tagLore.add("");
        tagLore.add(clan.isLeader(me) ? Lang.tr("heart.tags.click1") : Lang.tr("heart.tags.leader-gives"));
        if (clan.isLeader(me)) {
            tagLore.add(Lang.tr("heart.tags.click2"));
        }
        m.set(17, Menu.icon(Material.NAME_TAG, Lang.tr("heart.tags.name"), tagLore), p -> {
            if (clan.isLeader(p.getUniqueId()) && clan.members().size() < 2) {
                p.sendActionBar(Component.text(Lang.tr("heart.tags.alone"), NamedTextColor.YELLOW));
            }
        });
        int slot = 18;
        List<Map.Entry<UUID, Clan.Role>> members = new ArrayList<>(clan.members().entrySet());
        members.sort((a, b) -> a.getValue().compareTo(b.getValue()));
        for (Map.Entry<UUID, Clan.Role> entry : members) {
            if (slot >= 54) {
                break;
            }
            UUID id = entry.getKey();
            List<String> lore = new ArrayList<>();
            String badges = clan.badges(id);
            lore.add(badges.isEmpty() ? Lang.tr("heart.member.default") : badges);
            lore.add(Bukkit.getPlayer(id) != null ? Lang.tr("heart.member.online") : Lang.tr("heart.member.offline"));
            lore.add(newbie.isExperienced(id) ? Lang.tr("heart.member.experienced") : Lang.tr("heart.member.newbie"));
            if (clan.isLeader(me) && !id.equals(me)) {
                lore.add(Lang.tr("heart.member.click1"));
                lore.add(Lang.tr("heart.member.click2"));
            }
            m.set(slot++, head(id, nameOf(id), lore), p -> {
                if (clan.isLeader(p.getUniqueId()) && !id.equals(p.getUniqueId())) {
                    memberMenu(p, clan, id);
                }
            });
        }
        m.open(player);
    }

    // --- Nodes and proxies ---

    private void spheresMenu(Player player, Clan clan) {
        HeartholdConfig c = config.get();
        var px = c.newbie();
        boolean leader = clan.isLeader(player.getUniqueId());
        Menu m = new Menu(6, Component.text(Lang.tr("heart.spheres.name")));
        m.set(1, Menu.icon(c.node().material(), Sphere.Kind.NODE.title(),
                Lang.tr("heart.node.lore1"),
                Lang.tr("heart.node.lore2"),
                Lang.tr("heart.node.lore3", c.node().radius()),
                Lang.tr("heart.node.lore4"),
                Lang.tr("heart.sphere.activates-in", c.node().activationMinutes()),
                Lang.tr("heart.sphere.upkeep", f0(c.upkeep().nodePerDay())),
                Lang.tr("heart.node.lore5"),
                Lang.tr("heart.node.lore6")), null);
        m.set(7, Menu.icon(px.proxy().material(), Sphere.Kind.PROXY.title(),
                Lang.tr("heart.proxy.lore1"),
                Lang.tr("heart.proxy.lore2", px.proxy().radius()),
                Lang.tr("heart.proxy.lore3"),
                Lang.tr("heart.proxy.lore4"),
                Lang.tr("heart.proxy.lore5"),
                Lang.tr("heart.sphere.activates-in", px.proxy().activationMinutes()),
                Lang.tr("heart.sphere.upkeep", f0(px.proxyPerDay())),
                Lang.tr("heart.proxy.lore6")), null);
        String nodeCost = Lang.tr("heart.buy.cost", f0(c.nodeCost()));
        m.set(10, Menu.icon(Material.EMERALD, Lang.tr("heart.buy.node"),
                Lang.tr("heart.spheres.nodes", territory.nodeCount(clan.id()), c.maxNodes()), nodeCost,
                leader ? Lang.tr("heart.buy.click") : Lang.tr("heart.buy.leader")), p -> {
            if (clan.isLeader(p.getUniqueId())) {
                p.closeInventory();
                p.performCommand("clan node");
            }
        });
        m.set(16, Menu.icon(Material.EMERALD, Lang.tr("heart.buy.proxy"),
                Lang.tr("heart.spheres.proxies", territory.proxyCount(clan.id()), px.maxProxies()),
                Lang.tr("heart.buy.cost", f0(px.proxyCost())),
                leader ? Lang.tr("heart.buy.click") : Lang.tr("heart.buy.leader")), p -> {
            if (clan.isLeader(p.getUniqueId())) {
                p.closeInventory();
                p.performCommand("clan proxy");
            }
        });
        int slot = 27;
        for (Sphere s : territory.spheresOf(clan.id())) {
            if (slot >= 53) {
                break;
            }
            long now = System.currentTimeMillis();
            Material icon = switch (s.kind()) {
                case HEART -> c.heart().material();
                case NODE -> c.node().material();
                case PROXY -> px.proxy().material();
            };
            m.set(slot++, Menu.icon(icon, Lang.tr("heart.sphere.entry", s.kind().title(), s.x(), s.z()),
                    Lang.tr("heart.sphere.at", s.world(), s.x(), s.y(), s.z()), Lang.tr("heart.sphere.radius", s.radius()),
                    s.isActive(now) ? Lang.tr("heart.sphere.active")
                            : Lang.tr("heart.sphere.activating", TerritoryService.timeLeft(s, now)),
                    Lang.tr("heart.sphere.details")), p -> sphereMenu(p, clan, s));
        }
        m.set(53, Menu.icon(Material.ARROW, Lang.tr("heart.back")), p -> open(p, clan));
        m.open(player);
    }

    /** A single sphere: location, show the boundary, compass, remove (nodes and proxies). */
    private void sphereMenu(Player player, Clan clan, Sphere s) {
        long now = System.currentTimeMillis();
        Menu m = new Menu(3, Component.text(Lang.tr("heart.sphere.entry", s.kind().title(), s.x(), s.z())));
        List<String> info = new ArrayList<>();
        info.add(Lang.tr("heart.sphere.at", s.world(), s.x(), s.y(), s.z()));
        info.add(Lang.tr("heart.sphere.radius", s.radius()));
        if (s.isActive(now)) {
            info.add(Lang.tr("heart.sphere.active"));
        } else {
            info.add(Lang.tr("heart.sphere.activating", TerritoryService.timeLeft(s, now)));
            info.add(Lang.tr("heart.sphere.pending1"));
            info.add(Lang.tr("heart.sphere.pending2"));
        }
        m.set(4, Menu.icon(Material.PAPER, s.kind().title(), info), null);
        m.set(10, Menu.icon(Material.COMPASS, Lang.tr("heart.sphere.compass")), p -> {
            p.closeInventory();
            org.bukkit.World w = Bukkit.getWorld(s.world());
            if (w != null) {
                p.setCompassTarget(new org.bukkit.Location(w, s.x() + 0.5, s.y(), s.z() + 0.5));
                p.sendActionBar(Component.text(Lang.tr("heart.sphere.compass-set", s.kind().title()), NamedTextColor.GOLD));
            }
        });
        m.set(12, Menu.icon(Material.GLOWSTONE_DUST, Lang.tr("heart.sphere.show"), Lang.tr("heart.sphere.show1"),
                Lang.tr("heart.sphere.show2")), p -> {
            p.closeInventory();
            showSphere(p, s);
        });
        if (s.kind() != Sphere.Kind.HEART) {
            boolean leader = clan.isLeader(player.getUniqueId());
            String denial = territory.removalDenial(s);
            List<String> lore = new ArrayList<>();
            lore.add(Lang.tr("heart.sphere.remove.lore1"));
            lore.add(Lang.tr("heart.sphere.remove.lore2"));
            if (denial != null) {
                lore.add("");
                lore.add(denial);
            }
            lore.add(!leader ? Lang.tr("heart.sphere.remove.leader")
                    : denial != null ? Lang.tr("heart.sphere.remove.denied") : Lang.tr("heart.sphere.remove.click"));
            m.set(16, Menu.icon(Material.BARRIER, Lang.tr("heart.sphere.remove.name"), lore), p -> {
                if (clan.isLeader(p.getUniqueId()) && territory.removalDenial(s) == null) {
                    confirmRemove(p, clan, s);
                }
            });
        }
        m.set(22, Menu.icon(Material.ARROW, Lang.tr("heart.back")), p -> spheresMenu(p, clan));
        m.open(player);
    }

    private void confirmRemove(Player leader, Clan clan, Sphere s) {
        Menu m = new Menu(3, Component.text(Lang.tr("heart.remove.title", s.kind().title().toLowerCase(Locale.ROOT))));
        m.set(11, Menu.icon(Material.RED_CONCRETE, Lang.tr("heart.remove.yes"),
                Lang.tr("heart.sphere.at", s.kind().title(), s.x(), s.y(), s.z()),
                Lang.tr("heart.remove.lore1"),
                Lang.tr("heart.remove.lore2", s.radius()),
                Lang.tr("heart.remove.lore3")), p -> {
            p.closeInventory();
            String denial = territory.removalDenial(s);
            if (denial != null) {
                p.sendMessage(Component.text(denial, NamedTextColor.RED));
                return;
            }
            try {
                territory.removeNode(s);
            } catch (ClanException ex) {
                p.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
                return;
            }
            org.bukkit.World w = Bukkit.getWorld(s.world());
            if (w != null) {
                w.getBlockAt(s.x(), s.y(), s.z()).setType(Material.AIR);
            }
            ClanService.broadcast(clan, Component.text(Lang.tr("heart.remove.done", s.kind().title(), s.x(), s.y(), s.z()),
                    NamedTextColor.GOLD));
        });
        m.set(15, Menu.icon(Material.GREEN_CONCRETE, Lang.tr("heart.remove.no")), p -> sphereMenu(p, clan, s));
        m.open(leader);
    }

    /** Particles along the sphere's equator and a pillar above the block for 15 seconds, visible only to this player. */
    private void showSphere(Player player, Sphere s) {
        org.bukkit.World w = Bukkit.getWorld(s.world());
        if (w == null || !player.getWorld().equals(w)) {
            player.sendActionBar(Component.text(Lang.tr("heart.sphere.other-world"), NamedTextColor.RED));
            return;
        }
        org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("Hearthold");
        if (plugin == null) {
            return;
        }
        player.sendActionBar(Component.text(Lang.tr("heart.sphere.shown", s.kind().title(), s.x(), s.y(), s.z()),
                NamedTextColor.GOLD));
        int[] runs = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (++runs[0] > 15 || !player.isOnline()) {
                task.cancel();
                return;
            }
            var dust = new org.bukkit.Particle.DustOptions(org.bukkit.Color.AQUA, 1.5f);
            int points = Math.max(32, s.radius() * 6);
            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2 * i / points;
                player.spawnParticle(org.bukkit.Particle.DUST, s.x() + 0.5 + Math.cos(a) * s.radius(), s.y() + 0.5,
                        s.z() + 0.5 + Math.sin(a) * s.radius(), 1, dust);
            }
            for (int y = 0; y < 30; y++) {
                player.spawnParticle(org.bukkit.Particle.END_ROD, s.x() + 0.5, s.y() + 1 + y, s.z() + 0.5, 1, 0, 0, 0, 0);
            }
        }, 0L, 20L);
    }

    private ItemStack levelIcon(Clan clan, UUID viewer) {
        HeartholdConfig.BaseLevel now = levels.of(clan.id());
        List<String> lore = new ArrayList<>();
        lore.add(Lang.tr("heart.level.hp", now.hp()));
        lore.add(Lang.tr("heart.level.surcharge", f1(now.upkeepPerDay())));
        Optional<HeartholdConfig.BaseLevel> next = levels.next(clan.id());
        if (next.isEmpty()) {
            lore.add(Lang.tr("heart.level.max"));
        } else {
            HeartholdConfig.BaseLevel n = next.get();
            lore.add("");
            lore.add(Lang.tr("heart.level.next", n.name()));
            lore.add(Lang.tr("heart.level.next-hp", n.hp()));
            lore.add(Lang.tr("heart.level.next-upkeep", f1(n.upkeepPerDay())));
            lore.add(Lang.tr("heart.level.next-cost", f0(n.upgradeCost()), f1(economy.emeralds(clan.id()))));
            lore.add(clan.isLeader(viewer) ? Lang.tr("heart.level.click") : Lang.tr("heart.level.leader"));
        }
        return Menu.icon(Material.EXPERIENCE_BOTTLE, Lang.tr("heart.level.name", now.name()), lore);
    }

    private void confirmUpgrade(Player leader, Clan clan) {
        HeartholdConfig.BaseLevel n = levels.next(clan.id()).orElseThrow();
        Menu m = new Menu(3, Component.text(Lang.tr("heart.upgrade.title", n.name())));
        m.set(11, Menu.icon(Material.LIME_CONCRETE, Lang.tr("heart.upgrade.yes"),
                Lang.tr("heart.upgrade.cost", f0(n.upgradeCost())),
                Lang.tr("heart.upgrade.hp", n.hp()),
                Lang.tr("heart.upgrade.upkeep", f1(n.upkeepPerDay()))), p -> {
            try {
                HeartholdConfig.BaseLevel done = levels.upgrade(clan, p.getUniqueId());
                ClanService.broadcast(clan, Component.text(Lang.tr("heart.upgrade.done", done.name(), done.hp()),
                        NamedTextColor.GREEN));
            } catch (ClanException ex) {
                p.sendActionBar(Component.text(ex.getMessage(), NamedTextColor.RED));
            }
            open(p, clan);
        });
        m.set(15, Menu.icon(Material.RED_CONCRETE, Lang.tr("heart.no-back")), p -> open(p, clan));
        m.open(leader);
    }

    private List<String> summary(Clan clan) {
        List<String> lines = new ArrayList<>();
        if (levels != null) {
            HeartholdConfig.BaseLevel lv = levels.of(clan.id());
            lines.add(Lang.tr("heart.summary.level", lv.name(), lv.hp()));
        }
        double emeralds = economy.emeralds(clan.id());
        double cost = economy.dailyCost(clan.id());
        lines.add(Lang.tr("heart.summary.upkeep", f1(emeralds)));
        lines.add(cost > 0 ? Lang.tr("heart.summary.spend-days", f1(cost), f1(emeralds / cost))
                : Lang.tr("heart.summary.spend", f1(cost)));
        lines.add(economy.isProtected(clan.id()) ? Lang.tr("heart.summary.protected")
                : Lang.tr("heart.summary.unprotected"));
        lines.add(Lang.tr("heart.summary.energy", f0(economy.energy(clan.id())),
                f0(config.get().energy().heartMax()), f0(economy.energyPerHour(clan.id()))));
        territory.heartOf(clan.id()).ifPresent(s -> lines.add(Lang.tr("heart.summary.heart", s.x(), s.y(), s.z(),
                s.radius())));
        lines.add(Lang.tr("heart.summary.spheres", territory.nodeCount(clan.id()), territory.proxyCount(clan.id())));
        if (newbie.isProtected(clan.id())) {
            long hours = Math.max(0, (newbie.until(clan.id()) - System.currentTimeMillis()) / 3_600_000);
            lines.add(Lang.tr("heart.summary.newbie", hours));
        }
        lines.add(Lang.tr("heart.summary.members", clan.members().size()));
        return lines;
    }

    private void takeCell(Player p, Clan clan) {
        HeartholdConfig.EnergySettings e = config.get().energy();
        if (!economy.takeEnergy(clan.id(), e.cellSize())) {
            p.sendActionBar(Component.text(Lang.tr("heart.cell.not-enough", f0(e.cellSize()),
                    f0(economy.energy(clan.id()))), NamedTextColor.RED));
            return;
        }
        p.getInventory().addItem(cells.create(e.cellMaterial(), e.cellSize())).values()
                .forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
        p.sendActionBar(Component.text(Lang.tr("heart.cell.taken"), NamedTextColor.AQUA));
        open(p, clan);
    }

    // --- Member: tags ---

    private void memberMenu(Player leader, Clan clan, UUID member) {
        Menu m = new Menu(3, Component.text(nameOf(member)));
        m.set(4, head(member, nameOf(member), List.of(clan.badges(member).isEmpty()
                        ? Lang.tr("heart.member.default") : clan.badges(member))),
                null);
        int slot = 10;
        for (Clan.Tag tag : Clan.Tag.values()) {
            boolean on = clan.tagsOf(member).contains(tag);
            m.set(slot, Menu.icon(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    Lang.tr(on ? "heart.member.tag-on" : "heart.member.tag-off", tag.title()),
                    tag.description(), on ? Lang.tr("heart.member.revoke") : Lang.tr("heart.member.grant")), p -> {
                try {
                    clans.setTag(clan, p.getUniqueId(), member, tag, !on);
                } catch (ClanException ex) {
                    p.sendActionBar(Component.text(ex.getMessage(), NamedTextColor.RED));
                }
                memberMenu(p, clan, member);
            });
            slot += 2;
        }
        boolean trusted = clan.members().get(member) == Clan.Role.TRUSTED;
        String home = Clan.homeOf(member);
        m.set(22, Menu.icon(home != null ? Material.RED_BED : Material.LIGHT_GRAY_BED,
                home != null ? Lang.tr("heart.member.home", home) : Lang.tr("heart.member.no-home"),
                Lang.tr("heart.member.home1"), Lang.tr("heart.member.home2")), null);
        m.set(16, Menu.icon(trusted ? Material.LIME_DYE : Material.GRAY_DYE,
                Lang.tr(trusted ? "heart.member.trusted-yes" : "heart.member.trusted-no"),
                Lang.tr("heart.member.trusted-lore"),
                trusted ? Lang.tr("heart.member.revoke") : Lang.tr("heart.member.appoint")), p -> {
            try {
                clans.setTrusted(clan, member, !trusted);
            } catch (ClanException ex) {
                p.sendActionBar(Component.text(ex.getMessage(), NamedTextColor.RED));
            }
            memberMenu(p, clan, member);
        });
        m.set(18, Menu.icon(Material.ARROW, Lang.tr("heart.back")), p -> open(p, clan));
        m.open(leader);
    }

    // --- Invitation ---

    private void invitePicker(Player leader, Clan clan) {
        Menu m = new Menu(6, Component.text(Lang.tr("heart.invite.title")));
        int slot = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) {
                break;
            }
            if (clan.isMember(target.getUniqueId())) {
                continue;
            }
            Optional<Clan> other = clans.ofPlayer(target.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add(other.map(c -> Lang.tr("heart.invite.clan", c.name())).orElse(Lang.tr("heart.invite.no-clan")));
            boolean experienced = newbie.isExperienced(target.getUniqueId());
            lore.add(experienced ? Lang.tr("heart.member.experienced") : Lang.tr("heart.member.newbie"));
            if (experienced && newbie.isProtected(clan.id())) {
                lore.add(Lang.tr("heart.invite.ends-newbie"));
            }
            long hours = target.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600;
            lore.add(Lang.tr("heart.invite.played", hours));
            clans.invitedTo(target.getUniqueId()).flatMap(clans::byId)
                    .ifPresent(c -> lore.add(Lang.tr("heart.invite.already-invited", c.name())));
            lore.add(other.isPresent() ? Lang.tr("heart.invite.in-clan") : Lang.tr("heart.invite.click"));
            String name = target.getName();
            m.set(slot++, head(target.getUniqueId(), name, lore), p -> {
                if (other.isPresent()) {
                    return;
                }
                if (experienced && newbie.isProtected(clan.id())) {
                    confirmInvite(p, clan, name);
                } else {
                    p.closeInventory();
                    p.performCommand("clan invite " + name);
                }
            });
        }
        if (slot == 0) {
            m.set(22, Menu.icon(Material.PAPER, Lang.tr("heart.invite.nobody"), Lang.tr("heart.invite.nobody-lore")), null);
        }
        m.set(49, Menu.icon(Material.ARROW, Lang.tr("heart.back")), p -> open(p, clan));
        m.open(leader);
    }

    private void confirmInvite(Player leader, Clan clan, String name) {
        Menu m = new Menu(3, Component.text(Lang.tr("heart.invite.confirm.title")));
        m.set(11, Menu.icon(Material.RED_CONCRETE, Lang.tr("heart.invite.confirm.yes", name),
                Lang.tr("heart.invite.confirm.lore1"), Lang.tr("heart.invite.confirm.lore2")), p -> {
            p.closeInventory();
            p.performCommand("clan invite " + name + " confirm");
        });
        m.set(15, Menu.icon(Material.GREEN_CONCRETE, Lang.tr("heart.no-back")), p -> invitePicker(p, clan));
        m.open(leader);
    }

    // --- Helpers ---

    static String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    static ItemStack head(UUID id, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(l -> (Component) Component.text(l, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)).toList());
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    // --- Heart removal timer ---

    /**
     * Heart removal timer button for clan target, acting on behalf of clan actor (own clan or suzerain):
     * state, start with confirmation, cancellation by whoever started it.
     */
    private void removalButton(Menu m, int slot, Clan target, Clan actor, java.util.function.Consumer<Player> back) {
        var timer = removal.of(target.id());
        String days = String.format(Locale.ROOT, "%.0f", config.get().heartRemovalDays());
        if (timer.isPresent()) {
            String cancelDenial = removal.cancelDenial(target, actor.id());
            String by = timer.get().startedBy() == null ? Lang.tr("heart.removal.self")
                    : clans.byId(timer.get().startedBy()).map(Clan::name).orElse("?");
            m.set(slot, Menu.icon(Material.TNT, Lang.tr("heart.removal.in",
                            dev.hearthold.base.HeartRemovalService.left(timer.get().endsAt() - System.currentTimeMillis())),
                    Lang.tr("heart.removal.by", by), Lang.tr("heart.removal.lore1"),
                    Lang.tr("heart.removal.lore2"), cancelDenial == null ? Lang.tr("heart.removal.cancel") : cancelDenial), p -> {
                try {
                    removal.cancel(target, actor.id());
                } catch (ClanException ex) {
                    p.sendActionBar(Component.text(ex.getMessage(), NamedTextColor.RED));
                }
                back.accept(p);
            });
            return;
        }
        String denial = removal.startDenial(target, actor.id());
        m.set(slot, Menu.icon(denial == null ? Material.TNT : Material.GRAY_DYE, Lang.tr("heart.removal.name"),
                Lang.tr("heart.removal.start1", days), Lang.tr("heart.removal.start2"),
                Lang.tr("heart.removal.start3"), Lang.tr("heart.removal.start4"),
                denial == null ? Lang.tr("heart.removal.start-click") : denial), denial != null ? null
                : p -> ActionsMenu.confirm(p, Lang.tr("heart.removal.confirm.title"),
                        List.of(Lang.tr("heart.removal.confirm.clan", target.name()),
                        Lang.tr("heart.removal.confirm1", days), Lang.tr("heart.removal.confirm2"),
                        Lang.tr("heart.removal.confirm3")), q -> {
                    try {
                        removal.start(target, actor.id());
                    } catch (ClanException ex) {
                        q.sendActionBar(Component.text(ex.getMessage(), NamedTextColor.RED));
                    }
                    back.accept(q);
                }, back));
    }

    /** A vassal's heart as seen by its suzerain: base state and the removal timer. */
    private void suzerainMenu(Player player, Clan vassal, Clan own) {
        Menu m = new Menu(3, Component.text(Lang.tr("heart.vassal.title", vassal.name())));
        List<String> info = new ArrayList<>();
        info.add(Lang.tr("heart.vassal.yours"));
        info.add(economy.isProtected(vassal.id()) ? Lang.tr("heart.summary.protected")
                : Lang.tr("heart.vassal.unprotected"));
        info.add(Lang.tr("heart.summary.members", vassal.members().size()));
        m.set(4, Menu.icon(Material.BEACON, Lang.tr("heart.clan-heart", vassal.name()), info), null);
        removalButton(m, 13, vassal, own, p -> suzerainMenu(p, vassal, own));
        m.open(player);
    }

    /** An abandoned base: a player without a clan can claim it. */
    private void abandonedMenu(Player player, Clan clan) {
        Menu m = new Menu(3, Component.text(Lang.tr("heart.abandoned.title", clan.name())));
        List<String> info = new ArrayList<>();
        info.add(Lang.tr("heart.abandoned.empty"));
        removal.of(clan.id()).ifPresent(t -> info.add(Lang.tr("heart.abandoned.vanish",
                dev.hearthold.base.HeartRemovalService.left(t.endsAt() - System.currentTimeMillis()))));
        info.add(Lang.tr("heart.abandoned.upkeep", f1(economy.emeralds(clan.id()))));
        m.set(4, Menu.icon(Material.BEACON, Lang.tr("heart.clan-heart", clan.name()), info), null);
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
        if (own.isPresent()) {
            m.set(13, Menu.icon(Material.BARRIER, Lang.tr("heart.abandoned.cannot"),
                    Lang.tr("heart.abandoned.cannot1", own.get().name()), Lang.tr("heart.abandoned.cannot2"),
                    Lang.tr("heart.abandoned.cannot3"), Lang.tr("heart.abandoned.cannot4")), null);
        } else {
            m.set(13, Menu.icon(Material.LIME_BANNER, Lang.tr("heart.abandoned.claim"), Lang.tr("heart.abandoned.claim1"),
                    Lang.tr("heart.abandoned.claim2", clan.name()), Lang.tr("heart.abandoned.claim3"),
                    Lang.tr("heart.abandoned.claim4"), Lang.tr("heart.abandoned.claim5")),
                    p -> ActionsMenu.confirm(p, Lang.tr("heart.abandoned.confirm.title", clan.name()),
                            List.of(Lang.tr("heart.abandoned.confirm.lore")), q -> {
                                try {
                                    clans.claimAbandoned(clan, q.getUniqueId());
                                    removal.onClaimed(clan);
                                    Bukkit.broadcast(Component.text(Lang.tr("heart.abandoned.claimed", q.getName(),
                                            clan.name()), NamedTextColor.GOLD));
                                    open(q, clan);
                                } catch (ClanException ex) {
                                    q.closeInventory();
                                    q.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
                                }
                            }, q -> abandonedMenu(q, clan)));
        }
        m.open(player);
    }

    /**
     * Handles a click on a sphere: for the clan's own heart and nodes this menu opens; an abandoned base's heart
     * opens the "claim base" menu; a vassal's heart opens the suzerain menu with the removal timer.
     */
    public boolean handleSphereClick(Player player, Sphere sphere) {
        Optional<Clan> clan = clans.byId(sphere.clanId());
        if (clan.isEmpty()) {
            return false;
        }
        if (!clan.get().isMember(player.getUniqueId())) {
            if (removal == null || sphere.kind() != Sphere.Kind.HEART) {
                return false;
            }
            if (clan.get().members().isEmpty()) {
                abandonedMenu(player, clan.get());
                return true;
            }
            Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
            if (own.isPresent() && removal.isSuzerain(own.get().id(), clan.get().id())) {
                suzerainMenu(player, clan.get(), own.get());
                return true;
            }
            return false;
        }
        if (sphere.kind() == Sphere.Kind.HEART) {
            atHeart.add(player.getUniqueId());
        } else {
            atHeart.remove(player.getUniqueId());
        }
        open(player, clan.get());
        return true;
    }
}
