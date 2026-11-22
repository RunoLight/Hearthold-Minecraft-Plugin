package dev.hearthold.base;

import dev.hearthold.clan.AllyService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Defender bonus: clan members (and allies, if enabled) get effects while on the clan's active territory.
 * The effects are short and refreshed every 2 seconds, so they wear off soon after leaving the territory.
 */
public final class DefenderBonusTask implements Runnable {

    public static final long PERIOD_TICKS = 40L;
    private static final int DURATION_TICKS = 100;

    private final TerritoryService territory;
    private final ClanService clans;
    private final AllyService allies;
    private final Supplier<HeartholdConfig> config;

    public DefenderBonusTask(TerritoryService territory, ClanService clans, AllyService allies,
                             Supplier<HeartholdConfig> config) {
        this.territory = territory;
        this.clans = clans;
        this.allies = allies;
        this.config = config;
    }

    @Override
    public void run() {
        HeartholdConfig c = config.get();
        if (c.defenderEffects().isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
            if (own.isEmpty()) {
                continue;
            }
            Optional<Clan> owner = territory.ownerOf(player.getLocation().getBlock());
            if (owner.isEmpty()) {
                continue;
            }
            boolean defender = owner.get().id().equals(own.get().id())
                    || (c.defenderBonusForAllies() && allies.areAllies(owner.get().id(), own.get().id()));
            if (!defender) {
                continue;
            }
            c.defenderEffects().forEach((type, amplifier) ->
                    player.addPotionEffect(new PotionEffect(type, DURATION_TICKS, amplifier, true, false, true)));
        }
    }
}
