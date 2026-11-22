package dev.hearthold.ui;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.web.ScoutingService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Clan scouting in the compass: current tier, its upkeep cost, and changing the tier (leader only).
 * An enabled tier stays on for at least a day, so it cannot be switched on, used to view the map and switched off.
 */
public final class ScoutingMenu {

    private static final Material[] ICONS = {Material.GRAY_DYE, Material.SPYGLASS, Material.ENDER_EYE, Material.ENDER_PEARL};

    private final ScoutingService scouting;
    private final ClanService clans;
    private final Supplier<HeartholdConfig> config;

    public ScoutingMenu(ScoutingService scouting, ClanService clans, Supplier<HeartholdConfig> config) {
        this.scouting = scouting;
        this.clans = clans;
        this.config = config;
    }

    private static String describe(int tier, HeartholdConfig.WebSettings w) {
        return switch (tier) {
            case 0 -> Lang.tr("ui.scouting.tier0");
            case 1 -> Lang.tr("ui.scouting.tier1", w.slowDelaySeconds());
            case 2 -> Lang.tr("ui.scouting.tier2", w.slowDelaySeconds());
            default -> Lang.tr("ui.scouting.tier3", w.fastDelaySeconds());
        };
    }

    public void open(Player player, Consumer<Player> back) {
        Optional<Clan> clan = clans.ofPlayer(player.getUniqueId());
        if (clan.isEmpty()) {
            player.sendActionBar(Component.text(Lang.tr("ui.scouting.no-clan"), NamedTextColor.GRAY));
            return;
        }
        Clan c = clan.get();
        HeartholdConfig.WebSettings w = config.get().web();
        int current = scouting.tier(c.id());
        long lock = scouting.lockedUntil(c.id());
        boolean locked = lock > System.currentTimeMillis();
        boolean leader = c.isLeader(player.getUniqueId());

        Menu m = new Menu(3, Component.text(Lang.tr("ui.scouting.title")));
        List<String> info = new ArrayList<>();
        info.add(describe(current, w));
        info.add(Lang.tr("ui.scouting.upkeep", String.format(Locale.ROOT, "%.0f", w.costOf(current))));
        if (locked) {
            info.add(Lang.tr("ui.scouting.lowerable-after", new SimpleDateFormat("dd.MM HH:mm").format(new Date(lock))));
        }
        info.add(Lang.tr("ui.scouting.min-hours", ScoutingService.MIN_HOURS));
        info.add(Lang.tr("ui.scouting.map-hint"));
        m.set(4, Menu.icon(ICONS[Math.min(current, 3)], Lang.tr("ui.scouting.current", current), info), null);

        for (int t = 0; t <= ScoutingService.MAX_TIER; t++) {
            int tier = t;
            List<String> lore = new ArrayList<>();
            lore.add(describe(t, w));
            lore.add(Lang.tr("ui.scouting.upkeep", String.format(Locale.ROOT, "%.0f", w.costOf(t))));
            if (t == current) {
                lore.add(Lang.tr("ui.scouting.enabled"));
            } else if (!leader) {
                lore.add(Lang.tr("ui.scouting.leader-only"));
            } else if (t < current && locked) {
                lore.add(Lang.tr("ui.scouting.locked"));
            } else {
                lore.add(t > current ? Lang.tr("ui.scouting.click-enable", ScoutingService.MIN_HOURS) : Lang.tr("ui.scouting.click-lower"));
            }
            m.set(10 + t * 2, Menu.icon(ICONS[t], Lang.tr("ui.scouting.tier", t), lore), p -> {
                if (!c.isLeader(p.getUniqueId()) || tier == scouting.tier(c.id())) {
                    return;
                }
                if (tier > scouting.tier(c.id())) {
                    confirm(p, c, tier, back);
                } else {
                    apply(p, c, tier, back);
                }
            });
        }
        if (back != null) {
            m.set(18, Menu.icon(Material.ARROW, Lang.tr("ui.back")), back);
        }
        m.open(player);
    }

    private void confirm(Player player, Clan clan, int tier, Consumer<Player> back) {
        HeartholdConfig.WebSettings w = config.get().web();
        Menu m = new Menu(3, Component.text(Lang.tr("ui.scouting.confirm.title", tier)));
        m.set(11, Menu.icon(Material.LIME_CONCRETE, Lang.tr("ui.scouting.confirm.yes"),
                describe(tier, w),
                Lang.tr("ui.scouting.upkeep", String.format(Locale.ROOT, "%.0f", w.costOf(tier))),
                Lang.tr("ui.scouting.confirm.lock", ScoutingService.MIN_HOURS)), p -> apply(p, clan, tier, back));
        m.set(15, Menu.icon(Material.RED_CONCRETE, Lang.tr("ui.scouting.confirm.no")), p -> open(p, back));
        m.open(player);
    }

    private void apply(Player p, Clan clan, int tier, Consumer<Player> back) {
        String error = scouting.change(clan.id(), tier);
        if (error != null) {
            p.sendActionBar(Component.text(error, NamedTextColor.RED));
        } else {
            ClanService.broadcast(clan, Component.text(Lang.tr("ui.scouting.changed", tier,
                    String.format(Locale.ROOT, "%.0f", config.get().web().costOf(tier))), NamedTextColor.GREEN));
        }
        open(p, back);
    }
}
