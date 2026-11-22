package dev.hearthold.weight;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** /weight: how much the player carries and what weighs the most. */
public final class WeightCommand implements CommandExecutor {

    private final WeightService weights;
    private final Supplier<HeartholdConfig> config;

    public WeightCommand(WeightService weights, Supplier<HeartholdConfig> config) {
        this.weights = weights;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("weight.command.players-only"));
            return true;
        }
        var w = config.get().weight();
        double load = weights.weight(player);
        double cap = weights.capacity(player);
        player.sendMessage(Component.text(Lang.tr("weight.command.summary", String.format(Locale.ROOT, "%.2f", load),
                String.format(Locale.ROOT, "%.1f", cap), String.format(Locale.ROOT, "%.1f", cap * w.slowTier()),
                String.format(Locale.ROOT, "%.1f", cap * w.heavyTier()), String.format(Locale.ROOT, "%.1f", cap * w.stopTier())),
                NamedTextColor.GRAY));
        long potion = weights.potionMinutesLeft(player);
        if (potion > 0) {
            player.sendMessage(Component.text(Lang.tr("weight.command.potion",
                    String.format(Locale.ROOT, "%.0f", weights.potionBonus(player)), potion), NamedTextColor.GREEN));
        }
        List<ItemStack> items = new ArrayList<>(Arrays.asList(player.getInventory().getContents()));
        items.removeIf(Objects::isNull);
        items.sort(Comparator.comparingDouble((ItemStack i) -> weights.weight(i)).reversed());
        for (ItemStack item : items.subList(0, Math.min(5, items.size()))) {
            player.sendMessage(Component.text(String.format(Locale.ROOT, "  %s ×%d — %.2f",
                    item.getType().name().toLowerCase(Locale.ROOT), item.getAmount(), weights.weight(item)),
                    NamedTextColor.DARK_GRAY));
        }
        return true;
    }
}
