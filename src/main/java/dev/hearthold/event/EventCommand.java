package dev.hearthold.event;

import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /event — public event list: location, status, timer.
 * Admin: /event add <wave|chest> <name> [radius] (the core is the block you look at), remove <name>, start <name>.
 */
public final class EventCommand implements TabExecutor {

    private final EventService events;

    public EventCommand(EventService events) {
        this.events = events;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("hearthold.admin");
        switch (sub) {
            case "add" -> {
                if (admin) {
                    add(sender, args);
                }
            }
            case "remove" -> {
                if (admin && args.length >= 2) {
                    events.byName(args[1]).ifPresentOrElse(s -> {
                        events.remove(s);
                        ok(sender, Lang.tr("event.cmd.removed", s.name()));
                    }, () -> err(sender, Lang.tr("event.cmd.not-found")));
                }
            }
            case "start" -> {
                if (admin && args.length >= 2) {
                    Optional<EventService.Site> s = events.byName(args[1]);
                    if (s.isEmpty()) {
                        err(sender, Lang.tr("event.cmd.not-found"));
                    } else if (s.get().type() == EventService.Type.WAVE) {
                        String error = events.startWaves(s.get(), null);
                        if (error != null) {
                            err(sender, error);
                        }
                    } else {
                        events.announce(s.get());
                    }
                }
            }
            case "compass" -> {
                if (sender instanceof org.bukkit.entity.Player p && args.length >= 2) {
                    events.byName(args[1]).flatMap(EventService.Site::core).ifPresentOrElse(loc -> {
                        p.setCompassTarget(loc);
                        int d = loc.getWorld().equals(p.getWorld()) ? (int) loc.distance(p.getLocation()) : -1;
                        p.sendActionBar(Component.text(d >= 0
                                ? Lang.tr("event.cmd.compass.distance", args[1], d)
                                : Lang.tr("event.cmd.compass.other-world", args[1]), NamedTextColor.GOLD));
                    }, () -> err(sender, Lang.tr("event.cmd.not-found")));
                }
            }
            default -> list(sender);
        }
        return true;
    }

    private void add(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 3) {
            err(sender, Lang.tr("event.cmd.add.usage"));
            return;
        }
        EventService.Type type;
        try {
            type = EventService.Type.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            err(sender, Lang.tr("event.cmd.add.type"));
            return;
        }
        if (events.byName(args[2]).isPresent()) {
            err(sender, Lang.tr("event.cmd.add.exists"));
            return;
        }
        Block core = player.getTargetBlockExact(8);
        if (core == null) {
            err(sender, Lang.tr("event.cmd.add.look-at-core"));
            return;
        }
        int radius = 16;
        if (args.length >= 4) {
            try {
                radius = Math.max(4, Integer.parseInt(args[3]));
            } catch (NumberFormatException ignored) {
                // default radius
            }
        }
        EventService.Site s = events.add(args[2], type, core, radius);
        ok(sender, Lang.tr("event.cmd.added", s.name(), s.coords(), radius));
    }

    private void list(CommandSender sender) {
        sender.sendMessage(Component.text(Lang.tr("event.cmd.list.header"), NamedTextColor.GOLD));
        if (events.all().isEmpty()) {
            sender.sendMessage(Component.text(Lang.tr("event.cmd.list.empty"), NamedTextColor.GRAY));
        }
        for (EventService.Site s : events.all()) {
            String timer = switch (s.state()) {
                case COOLDOWN, WAITING, ANNOUNCED, OPEN -> Lang.tr("event.cmd.list.timer", left(s.nextAt()));
                case ACTIVE -> Lang.tr("event.cmd.list.wave", s.wave() + 1);
                default -> "";
            };
            String kind = Lang.tr(s.type() == EventService.Type.WAVE ? "event.cmd.list.kind.wave" : "event.cmd.list.kind.chest");
            sender.sendMessage(Component.text(Lang.tr("event.cmd.list.entry", s.name(), kind, s.coords(),
                    s.state().title(), timer), NamedTextColor.GRAY).append(EventService.compassButton(s)));
        }
    }

    /** Time left until the moment: "12 min", "3 h 5 min". */
    static String left(long at) {
        long min = Math.max(0, (at - System.currentTimeMillis() + 59_999) / 60_000);
        return min >= 60 ? Lang.tr("event.time.hours-minutes", min / 60, min % 60) : Lang.tr("event.time.minutes", min);
    }

    private static void ok(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    private static void err(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("hearthold.admin");
        if (args.length == 1) {
            List<String> subs = admin ? List.of("list", "add", "remove", "start") : List.of("list");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (admin && args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return List.of("wave", "chest");
        }
        if (admin && args.length == 2 && List.of("remove", "start").contains(args[0].toLowerCase(Locale.ROOT))) {
            return events.all().stream().map(EventService.Site::name).toList();
        }
        return List.of();
    }
}
