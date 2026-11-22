package dev.hearthold.ui;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.collector.Collector;
import dev.hearthold.collector.CollectorGuide;
import dev.hearthold.collector.CollectorService;
import dev.hearthold.config.MapConfig;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Overview of the clan's collectors: location, whether it works (and why not), resource, yield without penalty,
 * the collector-count penalty and the result. Click points the compass at the collector.
 */
public final class CollectorsMenu {

    private final CollectorService collectors;
    private final CollectorGuide guide;
    private final ClanService clans;
    private final Supplier<HeartholdConfig> config;

    public CollectorsMenu(CollectorService collectors, CollectorGuide guide, ClanService clans,
                          Supplier<HeartholdConfig> config) {
        this.collectors = collectors;
        this.guide = guide;
        this.clans = clans;
        this.config = config;
    }

    public void open(Player player, Consumer<Player> back) {
        Optional<Clan> clan = clans.ofPlayer(player.getUniqueId());
        if (clan.isEmpty()) {
            player.sendActionBar(Component.text(Lang.tr("ui.collectors.no-clan"), NamedTextColor.GRAY));
            return;
        }
        MapConfig.CollectorSettings s = config.get().map().collector();
        List<Collector> own = collectors.all().stream().filter(c -> c.clanId().equals(clan.get().id())).toList();
        double eff = s.efficiencyFor(own.size());
        Menu m = new Menu(3, Component.text(Lang.tr("ui.collectors.title", own.size(), s.maxPerClan())));
        m.set(4, Menu.icon(Material.BOOK, Lang.tr("ui.collectors.penalty"),
                Lang.tr("ui.collectors.penalty.count", own.size()),
                Lang.tr("ui.collectors.penalty.each", String.format(Locale.ROOT, "%.0f", eff * 100)),
                Lang.tr("ui.collectors.penalty.value", String.format(Locale.ROOT, "%.0f", (1 - eff) * 100)),
                Lang.tr("ui.collectors.penalty.hint")), null);
        int slot = 9;
        for (Collector col : own) {
            if (slot >= 18) {
                break;
            }
            m.set(slot++, icon(col, eff), p -> {
                p.closeInventory();
                World w = Bukkit.getWorld(col.world());
                if (w == null) {
                    return;
                }
                p.setCompassTarget(new Location(w, col.x() + 0.5, col.y(), col.z() + 0.5));
                p.sendActionBar(Component.text(Lang.tr("ui.collectors.compass", col.x(), col.y(), col.z()),
                        NamedTextColor.GOLD));
            });
        }
        if (own.isEmpty()) {
            m.set(13, Menu.icon(Material.PAPER, Lang.tr("ui.collectors.none"),
                    Lang.tr("ui.collectors.none.1"), Lang.tr("ui.collectors.none.2"), Lang.tr("ui.collectors.none.3")), null);
        }
        boolean leader = clan.get().isLeader(player.getUniqueId());
        boolean full = own.size() >= s.maxPerClan();
        double cost = collectors.nextCost(clan.get().id());
        m.set(22, Menu.icon(Material.BARREL, Lang.tr("ui.collectors.get"),
                Lang.tr("ui.collectors.get.1"),
                Lang.tr("ui.collectors.get.2"),
                cost <= 0 ? Lang.tr("ui.collectors.get.free")
                        : Lang.tr("ui.collectors.get.price", String.format(Locale.ROOT, "%.0f", cost)),
                Lang.tr("ui.collectors.get.3"),
                Lang.tr("ui.collectors.get.4"),
                Lang.tr("ui.collectors.get.count", own.size(), s.maxPerClan()),
                Lang.tr(!leader ? "ui.collectors.get.leader-only" : full ? "ui.collectors.get.max" : "ui.collectors.get.click")), p -> {
            if (clan.get().isLeader(p.getUniqueId()) && !full) {
                p.closeInventory();
                p.performCommand("clan collector");
            }
        });
        m.set(26, Menu.icon(Material.MAP, Lang.tr("ui.collectors.blueprint"), Lang.tr("ui.collectors.blueprint.lore")), p -> {
            p.closeInventory();
            guide.sendInstructions(p);
        });
        if (back != null) {
            m.set(18, Menu.icon(Material.ARROW, Lang.tr("ui.back")), back);
        }
        m.open(player);
    }

    private org.bukkit.inventory.ItemStack icon(Collector col, double eff) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.tr("ui.collectors.location", col.world(), col.x(), col.y(), col.z()));
        int missing = col.isLoaded() ? guide.missing(col.block()).size() : -1;
        Optional<String> problem = collectors.problem(col);
        boolean works = problem.isEmpty() && missing <= 0;
        if (works) {
            lore.add(Lang.tr("ui.collectors.status.working"));
        } else {
            lore.add(Lang.tr("ui.collectors.status.broken", problem.orElse(Lang.tr("ui.collectors.status.incomplete"))));
        }
        if (missing > 0) {
            lore.add(Lang.tr("ui.collectors.missing", missing));
        } else if (missing < 0) {
            lore.add(Lang.tr("ui.collectors.unloaded"));
        }
        MapConfig.Region region = collectors.regionAt(col.world(), col.x(), col.z());
        if (region == null || region.perHour().isEmpty()) {
            lore.add(Lang.tr("ui.collectors.no-resource"));
        } else {
            lore.add(Lang.tr("ui.collectors.region", region.name()));
            region.perHour().forEach((mat, rate) -> lore.add(Lang.tr("ui.collectors.rate",
                    mat.name().toLowerCase(Locale.ROOT), String.format(Locale.ROOT, "%.1f", rate),
                    String.format(Locale.ROOT, "%.1f", rate * eff), String.format(Locale.ROOT, "%.1f", rate * (1 - eff)))));
        }
        lore.add(Lang.tr("ui.collectors.buffered", String.format(Locale.ROOT, "%.0f", col.buffered())));
        lore.add(Lang.tr("ui.collectors.click-compass"));
        Material iconType = works ? Material.BARREL : Material.RED_STAINED_GLASS;
        return Menu.icon(iconType, Lang.tr(works ? "ui.collectors.name" : "ui.collectors.name.idle", col.x(), col.z()), lore);
    }
}
