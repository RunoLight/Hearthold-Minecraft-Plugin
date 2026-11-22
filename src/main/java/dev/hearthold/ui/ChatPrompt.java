package dev.hearthold.ui;

import dev.hearthold.i18n.Lang;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Text input from a menu: the menu closes and the player types the answer in chat (the message is not sent to anyone).
 * The cancel word (or "cancel") or 2 minutes of silence cancels the input.
 */
public final class ChatPrompt implements Listener {

    private static final long TIMEOUT_MS = 120_000;

    private record Pending(BiConsumer<Player, String> onAnswer, long until) { }

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Closes the menu and waits for a chat line. {@code question} is what to ask, {@code hint} an example (may be null). */
    public void ask(Player player, String question, String hint, BiConsumer<Player, String> onAnswer) {
        player.closeInventory();
        pending.put(player.getUniqueId(), new Pending(onAnswer, System.currentTimeMillis() + TIMEOUT_MS));
        player.sendMessage(Component.text(question, NamedTextColor.GOLD));
        if (hint != null) {
            player.sendMessage(Component.text(hint, NamedTextColor.GRAY));
        }
        player.sendMessage(Component.text(Lang.tr("ui.prompt.instructions", Lang.tr("ui.prompt.cancel-word")),
                NamedTextColor.DARK_GRAY));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) {
            return;
        }
        if (System.currentTimeMillis() > p.until()) {
            return; // expired: treat as a normal chat message
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.isEmpty() || text.equalsIgnoreCase(Lang.tr("ui.prompt.cancel-word")) || text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Component.text(Lang.tr("ui.prompt.cancelled"), NamedTextColor.GRAY));
                return;
            }
            p.onAnswer().accept(player, text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
