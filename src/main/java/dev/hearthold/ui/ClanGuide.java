package dev.hearthold.ui;

import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/** Guide book for new clan members and pointing the compass at the base heart. */
public final class ClanGuide {

    private final ClanService clans;
    private final TerritoryService territory;

    public ClanGuide(ClanService clans, TerritoryService territory) {
        this.clans = clans;
        this.territory = territory;
    }

    /** Points the compass at the player's clan base heart. false if the clan has no heart yet. */
    public boolean pointToBase(Player player) {
        Optional<Location> heart = heartLocation(player);
        if (heart.isEmpty()) {
            player.sendActionBar(Component.text(Lang.tr("guide.no-heart"), NamedTextColor.GRAY));
            return false;
        }
        player.setCompassTarget(heart.get());
        player.sendActionBar(Component.text(Lang.tr("guide.compass-to-heart", (int) distance(player, heart.get())),
                NamedTextColor.GOLD));
        return true;
    }

    private static double distance(Player p, Location l) {
        return p.getWorld().equals(l.getWorld()) ? p.getLocation().distance(l) : 0;
    }

    private Optional<Location> heartLocation(Player player) {
        return clans.ofPlayer(player.getUniqueId())
                .flatMap(c -> territory.heartOf(c.id()))
                .flatMap(ClanGuide::location);
    }

    private static Optional<Location> location(Sphere s) {
        World w = Bukkit.getWorld(s.world());
        return w == null ? Optional.empty() : Optional.of(new Location(w, s.x() + 0.5, s.y(), s.z() + 0.5));
    }

    /** Called when the player has accepted an invitation. */
    public void onJoined(Player player, Clan clan) {
        pointToBase(player);
        open(player);
    }

    public void open(Player player) {
        Optional<Clan> clan = clans.ofPlayer(player.getUniqueId());
        String clanTitle = clan.map(c -> Lang.tr("guide.title.clan", c.name())).orElse(Lang.tr("guide.title.no-clan"));
        String where = clan.flatMap(c -> territory.heartOf(c.id()))
                .map(s -> s.x() + ", " + s.y() + ", " + s.z()).orElse(Lang.tr("guide.heart-not-placed"));
        List<Component> pages = List.of(
                page(title(clanTitle),
                        text(Lang.tr("guide.p1.before") + " "),
                        bold(Lang.tr("guide.p1.heart")),
                        text(Lang.tr("guide.p1.after", where))),
                page(title(Lang.tr("guide.p2.title")),
                        text(Lang.tr("guide.p2.text"))),
                page(title(Lang.tr("guide.p3.title")),
                        text("• "), bold(Lang.tr("guide.p3.heart")), text(Lang.tr("guide.p3.text"))),
                page(title(Lang.tr("guide.p4.title")),
                        text(Lang.tr("guide.p4.text"))));
        player.openBook(Book.book(Component.text(Lang.tr("guide.book-title")), Component.text("Hearthold"), pages));
    }

    private static Component page(Component... parts) {
        return Component.textOfChildren(parts);
    }

    private static Component title(String s) {
        return Component.text(s + "\n\n", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true);
    }

    private static Component bold(String s) {
        return Component.text(s, NamedTextColor.BLACK).decoration(TextDecoration.BOLD, true);
    }

    private static Component text(String s) {
        return Component.text(s, NamedTextColor.BLACK).decoration(TextDecoration.BOLD, false);
    }
}
