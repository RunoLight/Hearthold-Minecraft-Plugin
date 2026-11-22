package dev.hearthold.ui;

import dev.hearthold.clan.ClanService;
import dev.hearthold.collector.CollectorGuide;
import dev.hearthold.collector.ChunkResourceService;
import dev.hearthold.economy.ChunkEnergyService;
import java.util.List;
import dev.hearthold.event.EventService;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * Admin menu: every /hearthold command and event management as buttons. Each button runs the same command
 * as in chat, so permissions and logic are shared.
 */
public final class AdminMenu {

    public static final String PERMISSION = "hearthold.admin";

    private final ClanService clans;
    private final EventService events;
    private final CollectorGuide collectorGuide;
    private final ChunkEnergyService energy;
    private final ChunkResourceService resources;
    private final java.util.function.Supplier<dev.hearthold.config.HeartholdConfig> config;

    /** Resources offered for a chunk in addition to those already in the configured regions. */
    private static final List<Material> RESOURCE_CHOICES = List.of(
            Material.COAL, Material.IRON_INGOT, Material.COPPER_INGOT, Material.GOLD_INGOT, Material.REDSTONE,
            Material.LAPIS_LAZULI, Material.DIAMOND, Material.EMERALD, Material.QUARTZ, Material.COBBLESTONE,
            Material.SAND, Material.CLAY_BALL, Material.OAK_LOG, Material.SPRUCE_LOG, Material.WHEAT,
            Material.CARROT, Material.POTATO, Material.SUGAR_CANE, Material.CACTUS, Material.LEATHER,
            Material.WHITE_WOOL, Material.STRING, Material.GUNPOWDER, Material.BONE, Material.SLIME_BALL);

    private dev.hearthold.base.BaseLevelService levels;

    public void setLevels(dev.hearthold.base.BaseLevelService levels) {
        this.levels = levels;
    }

    private dev.hearthold.collector.CollectorService collectors;

    public void setCollectors(dev.hearthold.collector.CollectorService collectors) {
        this.collectors = collectors;
    }

    private dev.hearthold.base.TerritoryService territory;
    private dev.hearthold.newbie.NewbieService newbie;
    private dev.hearthold.craft.StationService stations;

    public void setTerritory(dev.hearthold.base.TerritoryService territory) {
        this.territory = territory;
    }

    public void setNewbie(dev.hearthold.newbie.NewbieService newbie) {
        this.newbie = newbie;
    }

    public void setStations(dev.hearthold.craft.StationService stations) {
        this.stations = stations;
    }

    private dev.hearthold.war.WarService wars;
    private dev.hearthold.economy.EconomyService economy;
    private dev.hearthold.body.BodyListener bodyListener;
    private dev.hearthold.weight.WeightService weights;
    private dev.hearthold.health.BlockHealthService health;
    private HudService hud;

    /** Services for the Testing menu buttons. */
    public void setTesting(dev.hearthold.war.WarService wars, dev.hearthold.economy.EconomyService economy,
                           dev.hearthold.body.BodyListener bodyListener, dev.hearthold.weight.WeightService weights,
                           dev.hearthold.health.BlockHealthService health, HudService hud) {
        this.wars = wars;
        this.economy = economy;
        this.bodyListener = bodyListener;
        this.weights = weights;
        this.health = health;
        this.hud = hud;
    }

    private dev.hearthold.war.CaptureTask capture;

    public void setCapture(dev.hearthold.war.CaptureTask capture) {
        this.capture = capture;
    }

    public AdminMenu(ClanService clans, EventService events, CollectorGuide collectorGuide,
                     ChunkEnergyService energy, ChunkResourceService resources,
                     java.util.function.Supplier<dev.hearthold.config.HeartholdConfig> config) {
        this.clans = clans;
        this.events = events;
        this.collectorGuide = collectorGuide;
        this.energy = energy;
        this.resources = resources;
        this.config = config;
    }

    public void open(Player player, Consumer<Player> back) {
        if (!player.hasPermission(PERMISSION)) {
            return;
        }
        Menu m = new Menu(6, Component.text(Lang.tr("admin.menu.title")));
        // Row 1: config and game mode.
        m.set(0, Menu.icon(Material.WRITABLE_BOOK, Lang.tr("admin.menu.reload"), "/hearthold reload"), run("hearthold reload"));
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        m.set(1, Menu.icon(creative ? Material.GRASS_BLOCK : Material.COMMAND_BLOCK,
                Lang.tr(creative ? "admin.menu.mode.survival" : "admin.menu.mode.creative"),
                Lang.tr("admin.menu.mode.lore")), p -> {
            p.setGameMode(p.getGameMode() == GameMode.CREATIVE ? GameMode.SURVIVAL : GameMode.CREATIVE);
            open(p, back);
        });
        // Row 1: prime time.
        m.set(3, Menu.icon(Material.CLOCK, Lang.tr("admin.menu.prime.status"), "/hearthold prime"), run("hearthold prime"));
        m.set(4, Menu.icon(Material.LIME_DYE, Lang.tr("admin.menu.prime.on"), Lang.tr("admin.menu.prime.on.lore")), run("hearthold prime on"));
        m.set(5, Menu.icon(Material.GRAY_DYE, Lang.tr("admin.menu.prime.off")), run("hearthold prime off"));
        m.set(6, Menu.icon(Material.LIGHT_BLUE_DYE, Lang.tr("admin.menu.prime.auto")), run("hearthold prime auto"));

        // Row 2: world inspection.
        m.set(9, Menu.icon(Material.IRON_PICKAXE, Lang.tr("admin.menu.hp"), Lang.tr("admin.menu.hp.lore")),
                run("hearthold hp"));
        m.set(10, Menu.icon(Material.ECHO_SHARD, Lang.tr("admin.menu.energy"), Lang.tr("admin.menu.energy.lore")), run("hearthold energy"));
        m.set(11, Menu.icon(Material.MAP, Lang.tr("admin.menu.region"), Lang.tr("admin.menu.region.lore")), run("hearthold region"));
        m.set(12, Menu.icon(Material.RED_SANDSTONE, Lang.tr("admin.menu.zones"), Lang.tr("admin.menu.zones.lore")), run("hearthold zones"));
        m.set(13, Menu.icon(Material.IRON_ORE, Lang.tr("admin.menu.ores"), Lang.tr("admin.menu.ores.lore")), run("hearthold ores"));
        m.set(15, Menu.icon(Material.GLOWSTONE_DUST, Lang.tr("admin.menu.set-energy"), Lang.tr("admin.menu.set-energy.lore")),
                p -> energyMenu(p, back));
        m.set(16, Menu.icon(Material.HOPPER, Lang.tr("admin.menu.set-resource"),
                Lang.tr("admin.menu.set-resource.lore1"), Lang.tr("admin.menu.set-resource.lore2")), p -> resourceMenu(p, back));

        // Row 3: map.
        m.set(18, Menu.icon(Material.FILLED_MAP, Lang.tr("admin.menu.pregen.status")), run("hearthold pregen status"));
        m.set(19, Menu.icon(Material.GREEN_CONCRETE, Lang.tr("admin.menu.pregen.start"),
                Lang.tr("admin.menu.pregen.start.lore")), p -> {
            p.closeInventory();
            p.performCommand("hearthold pregen start " + p.getWorld().getName());
        });
        m.set(20, Menu.icon(Material.RED_CONCRETE, Lang.tr("admin.menu.pregen.stop")), run("hearthold pregen stop"));

        // Row 4: economy.
        m.set(27, Menu.icon(Material.POTION, Lang.tr("admin.menu.potion"), Lang.tr("admin.menu.potion.lore")), run("hearthold potion"));
        m.set(28, Menu.icon(Material.VILLAGER_SPAWN_EGG, Lang.tr("admin.menu.buyer.spawn"), Lang.tr("admin.menu.buyer.spawn.lore")),
                run("hearthold buyer spawn"));
        m.set(29, Menu.icon(Material.BARRIER, Lang.tr("admin.menu.buyer.remove"), Lang.tr("admin.menu.buyer.remove.lore")), run("hearthold buyer remove"));
        m.set(30, Menu.icon(Material.EMERALD, 64, Lang.tr("admin.menu.upkeep"), Lang.tr("admin.menu.upkeep.lore")),
                p -> upkeepClanPicker(p, back));

        if (levels != null) {
            clans.ofPlayer(player.getUniqueId()).ifPresent(c -> {
                var lv = levels.of(c.id());
                m.set(31, Menu.icon(Material.EXPERIENCE_BOTTLE, Lang.tr("admin.menu.level", lv.name()),
                        Lang.tr("admin.menu.level.hp", lv.hp()), Lang.tr("admin.menu.level.lore")), null);
                m.set(32, Menu.icon(Material.LIME_DYE, Lang.tr("admin.menu.level.up"), Lang.tr("admin.menu.level.up.lore")), p -> {
                    levels.set(c.id(), levels.of(c.id()).level() + 1);
                    open(p, back);
                });
                m.set(33, Menu.icon(Material.RED_DYE, Lang.tr("admin.menu.level.down"), Lang.tr("admin.menu.level.down.lore")), p -> {
                    levels.set(c.id(), levels.of(c.id()).level() - 1);
                    open(p, back);
                });
            });
        }

        // Row 4: schematics.
        m.set(34, Menu.icon(Material.BARREL, Lang.tr("admin.menu.kit"),
                Lang.tr("admin.menu.kit.lore1"), Lang.tr("admin.menu.kit.lore2")), p -> {
            p.closeInventory();
            collectorGuide.giveKit(p);
        });
        m.set(35, Menu.icon(Material.PAPER, Lang.tr("admin.menu.blueprint"), Lang.tr("admin.menu.blueprint.lore")), p -> {
            p.closeInventory();
            collectorGuide.sendInstructions(p);
        });

        // Row 5: events.
        m.set(36, Menu.icon(Material.NETHER_STAR, Lang.tr("admin.events"), Lang.tr("admin.menu.events.lore")), p -> eventsMenu(p, back));
        m.set(38, Menu.icon(Material.COMPARATOR, Lang.tr("admin.menu.testing"), Lang.tr("admin.menu.testing.lore1"), Lang.tr("admin.menu.testing.lore2")),
                p -> testMenu(p, back));

        m.set(53, Menu.icon(Material.ARROW, Lang.tr("admin.back")), back);
        m.open(player);
    }

    // --- Chunk: energy ---

    private void energyMenu(Player player, Consumer<Player> back) {
        ChunkEnergyService.ChunkKey key = ChunkEnergyService.ChunkKey.of(player.getLocation().getBlock());
        double natural = energy.naturalMax(key);
        double cap = energy.cap(key);
        Menu m = new Menu(3, Component.text(Lang.tr("admin.energy.title", key.cx(), key.cz())));
        m.set(4, Menu.icon(Material.GLOWSTONE, Lang.tr("admin.energy.now", Math.round(energy.energy(key))),
                Lang.tr("admin.energy.natural-max", Math.round(natural)), Lang.tr("admin.energy.cap-value", Math.round(cap)),
                Lang.tr("admin.energy.regen", String.format(java.util.Locale.ROOT, "%.0f", energy.regenPerHour(key)))), null);
        double[] values = {0, natural * 0.25, natural * 0.5, natural, cap};
        String[] names = {Lang.tr("admin.energy.empty"), Lang.tr("admin.energy.quarter"), Lang.tr("admin.energy.half"),
                Lang.tr("admin.energy.natural"), Lang.tr("admin.energy.cap")};
        Material[] icons = {Material.BLACK_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE,
                Material.YELLOW_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE};
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            m.set(11 + i, Menu.icon(icons[i], names[i], "= " + Math.round(v)), p -> {
                energy.set(key, v);
                p.sendActionBar(Component.text(Lang.tr("admin.energy.set", Math.round(energy.energy(key)))));
                energyMenu(p, back);
            });
        }
        m.set(22, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> open(p, back));
        m.open(player);
    }

    // --- Chunk: collector resources ---

    private void resourceMenu(Player player, Consumer<Player> back) {
        var loc = player.getLocation();
        ChunkResourceService.Key key = ChunkResourceService.Key.ofBlock(loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockZ());
        Menu m = new Menu(6, Component.text(Lang.tr("admin.resource.title", key.cx(), key.cz())));
        java.util.Map<Material, Double> current = resources.of(key);
        java.util.List<String> lore = new java.util.ArrayList<>();
        if (current.isEmpty()) {
            var region = config.get().map().regionAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
            lore.add(region == null ? Lang.tr("admin.resource.none-no-region")
                    : Lang.tr("admin.resource.none-region", region.name()));
        } else {
            current.forEach((mat, rate) -> lore.add(Lang.tr("admin.resource.rate", mat.name().toLowerCase(java.util.Locale.ROOT), rate)));
        }
        lore.add(Lang.tr("admin.resource.click-below"));
        m.set(4, Menu.icon(Material.HOPPER, Lang.tr("admin.resource.now"), lore), null);
        java.util.LinkedHashSet<Material> choices = new java.util.LinkedHashSet<>();
        config.get().map().regions().forEach(r -> choices.addAll(r.perHour().keySet()));
        choices.addAll(RESOURCE_CHOICES);
        int slot = 9;
        for (Material mat : choices) {
            if (slot >= 45) {
                break;
            }
            Double rate = current.get(mat);
            m.set(slot++, Menu.icon(mat, mat.name().toLowerCase(java.util.Locale.ROOT),
                    rate == null ? Lang.tr("admin.resource.unassigned") : Lang.tr("admin.resource.current", rate), Lang.tr("admin.resource.click")),
                    p -> rateMenu(p, key, mat, back));
        }
        m.set(45, Menu.icon(Material.BARRIER, Lang.tr("admin.resource.clear"), Lang.tr("admin.resource.clear.lore")), p -> {
            resources.clear(key);
            resourceMenu(p, back);
        });
        m.set(53, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> open(p, back));
        m.open(player);
    }

    private void rateMenu(Player player, ChunkResourceService.Key key, Material mat, Consumer<Player> back) {
        Menu m = new Menu(3, Component.text(Lang.tr("admin.rate.title", mat.name().toLowerCase(java.util.Locale.ROOT))));
        double[] rates = {1, 2, 5, 10, 20, 50, 100};
        for (int i = 0; i < rates.length; i++) {
            double r = rates[i];
            m.set(10 + i, Menu.icon(mat, (int) r, Lang.tr("admin.rate.per-hour", r)), p -> {
                resources.set(key, mat, r);
                resourceMenu(p, back);
            });
        }
        m.set(22, Menu.icon(Material.BARRIER, Lang.tr("admin.rate.remove")), p -> {
            resources.set(key, mat, 0);
            resourceMenu(p, back);
        });
        m.set(18, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> resourceMenu(p, back));
        m.open(player);
    }

    // --- Testing ---

    private static final Material[] FARM_CROPS = {
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS};

    private void testMenu(Player player, Consumer<Player> back) {
        Menu m = new Menu(6, Component.text(Lang.tr("admin.menu.testing")));
        var own = clans.ofPlayer(player.getUniqueId());
        String noClan = Lang.tr("admin.test.no-clan");

        // Row 1: items and structures.
        m.set(10, Menu.icon(Material.BONE_MEAL, 64, Lang.tr("admin.test.bonemeal"), Lang.tr("admin.test.bonemeal.lore1"), Lang.tr("admin.test.bonemeal.lore2")),
                p -> give(p, new org.bukkit.inventory.ItemStack(Material.BONE_MEAL, 64)));
        m.set(11, Menu.icon(Material.FARMLAND, Lang.tr("admin.test.farm"),
                Lang.tr("admin.test.farm.lore1"), Lang.tr("admin.test.farm.lore2"),
                Lang.tr("admin.test.farm.lore3")), p -> {
            p.closeInventory();
            buildFarm(p);
        });
        m.set(12, Menu.icon(Material.ANVIL, Lang.tr("admin.test.anvil"), Lang.tr("admin.test.anvil.lore1"),
                Lang.tr("admin.test.anvil.lore2"), Lang.tr("admin.test.anvil.lore3")), p -> {
            give(p, new org.bukkit.inventory.ItemStack(Material.ANVIL));
            p.setLevel(Math.max(p.getLevel(), 30));
        });
        m.set(13, Menu.icon(Material.ENCHANTING_TABLE, Lang.tr("admin.test.enchant"),
                Lang.tr("admin.test.enchant.lore1"), Lang.tr("admin.test.enchant.lore2"),
                Lang.tr("admin.test.enchant.lore3")), p -> {
            p.closeInventory();
            buildEnchantRoom(p);
        });
        m.set(14, Menu.icon(Material.HOPPER, Lang.tr("admin.test.fast-forward"), Lang.tr("admin.test.fast-forward.lore")),
                p -> fastForwardMenu(p, back));
        if (stations != null) {
            m.set(15, Menu.icon(Material.CLOCK, Lang.tr("admin.test.stations"),
                    Lang.tr(own.isPresent() ? "admin.test.stations.own" : "admin.test.stations.all"),
                    Lang.tr("admin.test.stations.lore")), p -> {
                int n = stations.completeNow(clans.ofPlayer(p.getUniqueId()).map(dev.hearthold.clan.Clan::id).orElse(null));
                p.sendActionBar(Component.text(Lang.tr("admin.test.stations.done", n)));
            });
        }
        if (weights != null) {
            m.set(16, Menu.icon(Material.OBSIDIAN, Lang.tr("admin.test.cargo"), Lang.tr("admin.test.cargo.lore1"),
                    Lang.tr("admin.test.cargo.lore2")), p -> cargoMenu(p, back));
        }

        // Row 2: own clan and newbie.
        if (own.isPresent()) {
            dev.hearthold.clan.Clan c = own.get();
            m.set(19, Menu.icon(Material.TNT, Lang.tr("admin.test.delete-own.named", c.name()),
                    Lang.tr("admin.test.delete-own.lore1"), Lang.tr("admin.test.delete-own.lore2"),
                    Lang.tr("admin.next-confirm")), p -> confirmDeleteClan(p, c, back));
        } else {
            m.set(19, Menu.icon(Material.GRAY_DYE, Lang.tr("admin.test.delete-own"), noClan), null);
        }
        if (newbie != null) {
            boolean used = newbie.hasUsed(player.getUniqueId()) || newbie.isExperienced(player.getUniqueId());
            if (own.isPresent()) {
                m.set(20, Menu.icon(Material.GRAY_DYE, Lang.tr("admin.test.newbie"),
                        Lang.tr("admin.test.newbie.outside-only"), Lang.tr("admin.test.newbie.leave-first")), null);
                dev.hearthold.clan.Clan c = own.get();
                if (newbie.isProtected(c.id())) {
                    m.set(21, Menu.icon(Material.SHIELD, Lang.tr("admin.test.newbie.end"),
                            Lang.tr("admin.test.newbie.end.lore1"), Lang.tr("admin.test.newbie.end.lore2")), p -> {
                        newbie.end(c, Lang.tr("admin.test.newbie.end-reason"));
                        testMenu(p, back);
                    });
                    m.set(22, Menu.icon(Material.CLOCK, Lang.tr("admin.test.newbie.expire"),
                            Lang.tr("admin.test.newbie.expire.lore1"), Lang.tr("admin.test.newbie.expire.lore2")), p -> {
                        newbie.expireIn(c.id(), 1);
                        p.sendActionBar(Component.text(Lang.tr("admin.test.newbie.expire.done")));
                    });
                } else {
                    m.set(21, Menu.icon(Material.GRAY_DYE, Lang.tr("admin.test.newbie.protection"), Lang.tr("admin.test.newbie.protection.none")), null);
                }
            } else {
                m.set(20, Menu.icon(used ? Material.TURTLE_HELMET : Material.LIME_DYE, Lang.tr("admin.test.newbie.enable"),
                        Lang.tr(used ? "admin.test.newbie.now-experienced" : "admin.test.newbie.now-newbie"),
                        Lang.tr("admin.test.newbie.enable.lore1"), Lang.tr("admin.test.newbie.enable.lore2"), Lang.tr("admin.test.newbie.enable.lore3")),
                        p -> {
                            if (clans.ofPlayer(p.getUniqueId()).isPresent()) {
                                p.sendActionBar(Component.text(Lang.tr("admin.test.newbie.leave-clan")));
                                return;
                            }
                            newbie.resetPlayer(p.getUniqueId());
                            p.sendMessage(Component.text(Lang.tr("admin.test.newbie.reset"), GREEN));
                            testMenu(p, back);
                        });
            }
        }
        if (territory != null) {
            m.set(23, Menu.icon(Material.BEACON, Lang.tr("admin.test.activate"),
                    own.isPresent() ? Lang.tr("admin.test.activate.lore") : noClan, Lang.tr("admin.test.activate.lore2")), p -> {
                own.ifPresent(c -> p.sendActionBar(Component.text(Lang.tr("admin.test.activate.done", territory.activateNow(c.id())))));
            });
        }
        if (health != null && territory != null) {
            m.set(24, Menu.icon(Material.GOLDEN_APPLE, Lang.tr("admin.test.heal"),
                    own.isPresent() ? Lang.tr("admin.test.heal.lore1") : noClan, Lang.tr("admin.test.heal.lore2")), p -> {
                own.ifPresent(c -> {
                    int n = health.healWhere(k -> c.id().equals(territory.claimAt(k.world(), k.x(), k.y(), k.z())));
                    p.sendActionBar(Component.text(Lang.tr("admin.test.heal.done", n)));
                });
            });
            m.set(25, Menu.icon(Material.IRON_PICKAXE, Lang.tr("admin.test.damage"), Lang.tr("admin.test.damage.lore1"),
                    Lang.tr("admin.test.damage.lore2")), this::damageTarget);
        }

        // Row 3: other clans, war, upkeep.
        m.set(28, Menu.icon(Material.ARMOR_STAND, Lang.tr("admin.test.dummy"),
                Lang.tr("admin.test.dummy.lore1"), Lang.tr("admin.test.dummy.lore2"),
                Lang.tr("admin.test.dummy.lore3", DUMMY_UPKEEP_DAYS), Lang.tr("admin.test.dummy.lore4"),
                Lang.tr("admin.test.dummy.lore5"), Lang.tr("admin.test.dummy.lore6"),
                Lang.tr("admin.test.dummy.lore7")), p -> {
            p.closeInventory();
            spawnDummyClan(p, DummyMode.UPKEEP);
        });
        m.set(29, Menu.icon(Material.ARMOR_STAND, Lang.tr("admin.test.dummy-no-upkeep"),
                Lang.tr("admin.test.dummy-no-upkeep.lore1"), Lang.tr("admin.test.dummy-no-upkeep.lore2"), Lang.tr("admin.test.dummy-no-upkeep.lore3")), p -> {
            p.closeInventory();
            spawnDummyClan(p, DummyMode.NO_UPKEEP);
        });
        m.set(27, Menu.icon(Material.COBWEB, Lang.tr("admin.test.dummy-abandoned"),
                Lang.tr("admin.test.dummy-abandoned.lore1"), Lang.tr("admin.test.dummy-abandoned.lore2"),
                Lang.tr("admin.test.dummy-abandoned.lore3"), Lang.tr("admin.test.dummy-abandoned.lore4")), p -> {
            p.closeInventory();
            spawnDummyClan(p, DummyMode.ABANDONED);
        });
        if (capture != null) {
            boolean fast = capture.isFast();
            m.set(40, Menu.icon(fast ? Material.LIME_DYE : Material.GRAY_DYE,
                    Lang.tr("admin.test.fast-capture", Lang.tr(fast ? "admin.on" : "admin.off")),
                    Lang.tr("admin.test.fast-capture.lore1", dev.hearthold.war.CaptureTask.FAST_SECONDS),
                    Lang.tr("admin.test.fast-capture.lore2"), Lang.tr("admin.test.fast-capture.lore3"),
                    Lang.tr(fast ? "admin.test.fast-capture.click-off" : "admin.test.fast-capture.click-on")), p -> {
                capture.setFast(!capture.isFast());
                p.sendActionBar(Component.text(Lang.tr(capture.isFast() ? "admin.test.fast-capture.enabled" : "admin.test.fast-capture.disabled")));
                testMenu(p, back);
            });
        }
        m.set(35, Menu.icon(Material.LAVA_BUCKET, Lang.tr("admin.test.dummy.delete-all"),
                Lang.tr("admin.test.dummy.delete-all.lore", DUMMY_PREFIX)), p -> {
            p.closeInventory();
            int n = 0;
            for (dev.hearthold.clan.Clan c : List.copyOf(clans.all())) {
                if (c.isDummy() && deleteClan(p, c)) {
                    n++;
                }
            }
            p.sendMessage(Component.text(Lang.tr("admin.test.dummy.deleted", n), GREEN));
        });
        if (wars != null) {
            m.set(30, Menu.icon(Material.IRON_SWORD, Lang.tr("admin.test.war"),
                    own.isPresent() ? Lang.tr("admin.test.war.lore") : noClan,
                    Lang.tr("admin.test.war.lore2")), p -> {
                if (own.isPresent()) {
                    warPicker(p, own.get(), back);
                }
            });
            m.set(31, Menu.icon(Material.WHITE_BANNER, Lang.tr("admin.test.end-wars"),
                    own.isPresent() ? Lang.tr("admin.test.end-wars.lore") : noClan), p -> {
                own.ifPresent(c -> {
                    int n = 0;
                    for (dev.hearthold.war.War w : List.copyOf(wars.ongoing())) {
                        if (w.attacker().equals(c.id()) || w.defender().equals(c.id())) {
                            wars.end(w, dev.hearthold.war.War.Outcome.PEACE);
                            n++;
                        }
                    }
                    p.sendActionBar(Component.text(Lang.tr("admin.test.end-wars.done", n)));
                });
            });
            m.set(32, Menu.icon(Material.MILK_BUCKET, Lang.tr("admin.test.reset-war"),
                    own.isPresent() ? Lang.tr("admin.test.reset-war.lore") : noClan, Lang.tr("admin.test.reset-war.lore2")), p -> {
                own.ifPresent(c -> {
                    wars.resetState(c.id());
                    p.sendActionBar(Component.text(Lang.tr("admin.test.reset-war.done")));
                });
            });
        }
        if (economy != null) {
            m.set(33, Menu.icon(Material.REDSTONE_BLOCK, Lang.tr("admin.test.upkeep-zero"),
                    own.isPresent() ? Lang.tr("admin.test.upkeep-zero.lore") : noClan, Lang.tr("admin.test.upkeep-zero.lore2")), p -> {
                own.ifPresent(c -> {
                    economy.adjust(c, -economy.emeralds(c.id()));
                    p.sendActionBar(Component.text(Lang.tr("admin.test.upkeep-zero.done")));
                });
            });
            m.set(34, Menu.icon(Material.EMERALD, Lang.tr("admin.test.upkeep-day"),
                    own.isPresent() ? Lang.tr("admin.test.upkeep-day.lore", String.format(java.util.Locale.ROOT, "%.1f",
                            economy.dailyCost(own.get().id()))) : noClan,
                    own.isPresent() ? Lang.tr("admin.test.upkeep-day.balance", String.format(java.util.Locale.ROOT, "%.1f",
                            economy.emeralds(own.get().id()))) : ""), p -> {
                own.ifPresent(c -> {
                    economy.adjust(c, -economy.dailyCost(c.id()));
                    p.sendActionBar(Component.text(Lang.tr("admin.test.upkeep-day.done",
                            String.format(java.util.Locale.ROOT, "%.1f", economy.emeralds(c.id())))));
                });
            });
        }

        // Row 4: bodies and HUD.
        if (bodyListener != null) {
            m.set(37, Menu.icon(Material.PLAYER_HEAD, Lang.tr("admin.test.body"),
                    Lang.tr("admin.test.body.lore1"), Lang.tr("admin.test.body.lore2"), Lang.tr("admin.test.body.lore3")), p -> {
                p.closeInventory();
                org.bukkit.Location at = p.getLocation().getBlock().getRelative(p.getFacing(), 2).getLocation()
                        .add(0.5, 0, 0.5);
                bodyListener.spawnTestBody(at, randomLoot());
                p.sendMessage(Component.text(Lang.tr("admin.test.body.spawned"), GREEN));
            });
        }
        if (hud != null) {
            String as = hud.viewAs(player.getUniqueId()).flatMap(clans::byId).map(dev.hearthold.clan.Clan::name).orElse(null);
            m.set(38, Menu.icon(Material.SPYGLASS, Lang.tr("admin.test.hud"),
                    as == null ? Lang.tr("admin.test.hud.own") : Lang.tr("admin.test.hud.as", as), Lang.tr("admin.test.hud.lore")), p -> hudPicker(p, back));
            m.set(39, Menu.icon(Material.ENDER_EYE, Lang.tr("admin.test.hud.reset")), p -> {
                hud.setViewAs(p.getUniqueId(), null);
                testMenu(p, back);
            });
        }
        m.set(49, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> open(p, back));
        m.open(player);
    }

    private static final net.kyori.adventure.text.format.NamedTextColor GREEN =
            net.kyori.adventure.text.format.NamedTextColor.GREEN;
    private static final net.kyori.adventure.text.format.NamedTextColor RED =
            net.kyori.adventure.text.format.NamedTextColor.RED;
    private static final String DUMMY_PREFIX = dev.hearthold.clan.Clan.DUMMY_PREFIX;
    /** How many days of upkeep a dummy gets on creation. */
    private static final int DUMMY_UPKEEP_DAYS = 30;

    private static final List<Material> HEAVY = List.of(
            Material.NETHERITE_BLOCK, Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.DIAMOND_BLOCK,
            Material.ANVIL, Material.OBSIDIAN, Material.COPPER_BLOCK, Material.DEEPSLATE, Material.STONE,
            Material.COBBLESTONE, Material.IRON_INGOT, Material.GOLD_INGOT, Material.SAND, Material.GRAVEL,
            Material.OAK_LOG, Material.WATER_BUCKET);

    private void cargoMenu(Player player, Consumer<Player> back) {
        Menu m = new Menu(4, Component.text(Lang.tr("admin.test.cargo")));
        m.set(4, Menu.icon(Material.CHEST, Lang.tr("admin.cargo.load",
                String.format(java.util.Locale.ROOT, "%.0f", weights.weight(player)),
                String.format(java.util.Locale.ROOT, "%.0f", weights.capacity(player))), Lang.tr("admin.cargo.click")), null);
        int slot = 9;
        for (Material mat : HEAVY) {
            int amount = mat.getMaxStackSize();
            m.set(slot++, Menu.icon(mat, amount, mat.name().toLowerCase(java.util.Locale.ROOT) + " ×" + amount,
                    Lang.tr("admin.cargo.stack-weight", String.format(java.util.Locale.ROOT, "%.1f", weights.weight(mat, amount)))), p -> {
                give(p, new org.bukkit.inventory.ItemStack(mat, amount));
                cargoMenu(p, back);
            });
        }
        m.set(31, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> testMenu(p, back));
        m.open(player);
    }

    private static org.bukkit.inventory.ItemStack[] randomLoot() {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        Material[] pool = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD,
                Material.COAL, Material.BREAD, Material.COOKED_BEEF, Material.ARROW, Material.OAK_LOG,
                Material.COBBLESTONE, Material.GOLDEN_APPLE, Material.REDSTONE};
        List<org.bukkit.inventory.ItemStack> items = new java.util.ArrayList<>();
        items.add(new org.bukkit.inventory.ItemStack(r.nextBoolean() ? Material.IRON_SWORD : Material.IRON_PICKAXE));
        int kinds = 4 + r.nextInt(5);
        for (int i = 0; i < kinds; i++) {
            Material mat = pool[r.nextInt(pool.length)];
            items.add(new org.bukkit.inventory.ItemStack(mat, 1 + r.nextInt(mat.getMaxStackSize())));
        }
        org.bukkit.inventory.ItemStack[] result = new org.bukkit.inventory.ItemStack[41];
        for (int i = 0; i < items.size(); i++) {
            result[i] = items.get(i);
        }
        return result;
    }

    /** Removes half the HP of the block the player is looking at. */
    private void damageTarget(Player p) {
        p.closeInventory();
        org.bukkit.block.Block block = p.getTargetBlockExact(8);
        if (block == null || block.getType().isAir()) {
            p.sendActionBar(Component.text(Lang.tr("admin.damage.look")));
            return;
        }
        var owner = territory.claimOf(block);
        int max = owner.isPresent()
                ? (levels != null ? levels.maxHp(owner.get().id()) : config.get().protectedMaxHp())
                : config.get().wildHp(block.getType());
        dev.hearthold.health.BlockKey key = dev.hearthold.health.BlockKey.of(block);
        double left = health.hit(key, max, Math.max(1, (int) Math.ceil(health.hp(key, max) / 2)));
        p.sendMessage(Component.text(Lang.tr("admin.damage.result",
                block.getType().name().toLowerCase(java.util.Locale.ROOT), String.format(java.util.Locale.ROOT, "%.0f", left), max,
                owner.map(c -> Lang.tr("admin.damage.base", c.name())).orElse(Lang.tr("admin.damage.wild"))), GREEN));
    }

    /** UPKEEP: base protected at once; NO_UPKEEP: unprotected base; ABANDONED: nobody left in the clan. */
    private enum DummyMode { UPKEEP, NO_UPKEEP, ABANDONED }

    private void spawnDummyClan(Player p, DummyMode mode) {
        boolean withUpkeep = mode == DummyMode.UPKEEP;
        if (territory == null) {
            return;
        }
        org.bukkit.block.Block block = p.getLocation().getBlock().getRelative(p.getFacing(), 3);
        dev.hearthold.clan.Clan dummy;
        String name = DUMMY_PREFIX + (1000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9000));
        try {
            dummy = clans.create(name, java.util.UUID.randomUUID());
        } catch (dev.hearthold.clan.ClanException e) {
            p.sendMessage(Component.text(e.getMessage(), RED));
            return;
        }
        Material previous = block.getType();
        block.setType(config.get().sphere(dev.hearthold.base.Sphere.Kind.HEART).material());
        try {
            territory.place(dummy, dev.hearthold.base.Sphere.Kind.HEART, block);
        } catch (dev.hearthold.clan.ClanException e) {
            block.setType(previous);
            try {
                clans.forceDisband(dummy);
            } catch (dev.hearthold.clan.ClanException ignored) {
                // the clan was just created
            }
            p.sendMessage(Component.text(Lang.tr("admin.dummy.heart-failed", e.getMessage()), RED));
            return;
        }
        territory.activateNow(dummy.id());
        if (withUpkeep && economy != null) {
            economy.adjust(dummy, Math.max(1, economy.dailyCost(dummy.id())) * DUMMY_UPKEEP_DAYS);
        }
        buildTestStructures(block, p.getFacing());
        if (mode == DummyMode.ABANDONED) {
            try {
                clans.abandon(dummy);
            } catch (dev.hearthold.clan.ClanException e) {
                p.sendMessage(Component.text(e.getMessage(), RED));
            }
        }
        boolean shielded = economy == null || economy.isProtected(dummy.id());
        p.sendMessage(Component.text(Lang.tr("admin.dummy.created", name, block.getX(), block.getY(), block.getZ(),
                Lang.tr(shielded ? "admin.dummy.created.protected" : "admin.dummy.created.unprotected"),
                Lang.tr(mode == DummyMode.ABANDONED ? "admin.dummy.created.abandoned" : "admin.dummy.created.war-hint")),
                GREEN));
    }

    /**
     * Test structures behind the dummy's heart (forward along the player's view): a platform, a wall of the main
     * balance blocks (2 of each, 3 high), a wall with a wooden and an iron door (the iron one with pressure
     * plates on both sides and a button), a lever, a trapdoor, lamps around a redstone block with a piston next
     * to it, and a lamp with a lever.
     */
    private static void buildTestStructures(org.bukkit.block.Block heart, org.bukkit.block.BlockFace forward) {
        org.bukkit.block.BlockFace right = switch (forward) {
            case NORTH -> org.bukkit.block.BlockFace.EAST;
            case EAST -> org.bukkit.block.BlockFace.SOUTH;
            case SOUTH -> org.bukkit.block.BlockFace.WEST;
            default -> org.bukkit.block.BlockFace.NORTH;
        };
        org.bukkit.block.BlockFace back = forward.getOppositeFace();
        java.util.function.Function<int[], org.bukkit.block.Block> at = c -> heart.getRelative(forward, c[0])
                .getRelative(right, c[1]).getRelative(org.bukkit.block.BlockFace.UP, c[2]);
        // Platform and clearing: u 3..11, v −10..10.
        for (int u = 3; u <= 11; u++) {
            for (int v = -10; v <= 10; v++) {
                at.apply(new int[]{u, v, -1}).setType(Material.SMOOTH_STONE, false);
                for (int y = 0; y <= 4; y++) {
                    at.apply(new int[]{u, v, y}).setType(Material.AIR, false);
                }
            }
        }
        // Wall of the main balance blocks (as in the web panel): u = 10.
        List<Material> walls = dev.hearthold.web.AdminWebData.BASE_BLOCKS.stream()
                .filter(m -> m.isSolid() && !org.bukkit.Tag.DOORS.isTagged(m) && !org.bukkit.Tag.TRAPDOORS.isTagged(m))
                .toList();
        int v0 = -walls.size();
        for (int i = 0; i < walls.size(); i++) {
            for (int dv = 0; dv < 2; dv++) {
                for (int y = 0; y < 3; y++) {
                    at.apply(new int[]{10, v0 + i * 2 + dv, y}).setType(walls.get(i), false);
                }
            }
        }
        // Wall with doors: u = 6, v −6..6.
        for (int v = -6; v <= 6; v++) {
            for (int y = 0; y < 3; y++) {
                at.apply(new int[]{6, v, y}).setType(Material.STONE_BRICKS, false);
            }
        }
        door(at.apply(new int[]{6, -4, 0}), Material.OAK_DOOR, forward);
        door(at.apply(new int[]{6, 0, 0}), Material.IRON_DOOR, forward);
        at.apply(new int[]{5, 0, 0}).setType(Material.STONE_PRESSURE_PLATE, false);
        at.apply(new int[]{7, 0, 0}).setType(Material.STONE_PRESSURE_PLATE, false);
        wallSwitch(at.apply(new int[]{5, 1, 1}), Material.STONE_BUTTON, back);
        wallSwitch(at.apply(new int[]{5, 3, 1}), Material.LEVER, back);
        // Trapdoor on the floor in front of the wall.
        org.bukkit.block.Block trap = at.apply(new int[]{4, -2, 0});
        trap.setType(Material.OAK_TRAPDOOR, false);
        if (trap.getBlockData() instanceof org.bukkit.block.data.type.TrapDoor td) {
            td.setFacing(back);
            trap.setBlockData(td, false);
        }
        // Redstone block, lamps on the sides and a piston next to it (u = 3..4, v −7..−5).
        at.apply(new int[]{3, -7, 0}).setType(Material.REDSTONE_LAMP, false);
        at.apply(new int[]{3, -5, 0}).setType(Material.REDSTONE_LAMP, false);
        org.bukkit.block.Block piston = at.apply(new int[]{4, -6, 0});
        org.bukkit.block.data.BlockData pd = Material.PISTON.createBlockData();
        ((org.bukkit.block.data.Directional) pd).setFacing(forward);
        piston.setBlockData(pd, false);
        at.apply(new int[]{3, -6, 0}).setType(Material.REDSTONE_BLOCK, true); // updates neighbours: lamps light up, the piston extends
        // Lamp with a lever on top.
        at.apply(new int[]{3, 6, 0}).setType(Material.REDSTONE_LAMP, false);
        org.bukkit.block.Block lever = at.apply(new int[]{3, 6, 1});
        org.bukkit.block.data.BlockData ld = Material.LEVER.createBlockData();
        if (ld instanceof org.bukkit.block.data.type.Switch sw) {
            sw.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.FLOOR);
            sw.setFacing(forward);
        }
        lever.setBlockData(ld, false);
    }

    private static void door(org.bukkit.block.Block bottom, Material type, org.bukkit.block.BlockFace facing) {
        for (int half = 0; half < 2; half++) {
            org.bukkit.block.data.BlockData d = type.createBlockData();
            if (d instanceof org.bukkit.block.data.type.Door door) {
                door.setFacing(facing);
                door.setHalf(half == 0 ? org.bukkit.block.data.Bisected.Half.BOTTOM : org.bukkit.block.data.Bisected.Half.TOP);
            }
            bottom.getRelative(org.bukkit.block.BlockFace.UP, half).setBlockData(d, false);
        }
    }

    /** A button or lever on a wall; facing is where the front side points (away from the wall). */
    private static void wallSwitch(org.bukkit.block.Block block, Material type, org.bukkit.block.BlockFace facing) {
        org.bukkit.block.data.BlockData d = type.createBlockData();
        if (d instanceof org.bukkit.block.data.type.Switch sw) {
            sw.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.WALL);
            sw.setFacing(facing);
        }
        block.setBlockData(d, false);
    }

    private void warPicker(Player player, dev.hearthold.clan.Clan own, Consumer<Player> back) {
        Menu m = new Menu(6, Component.text(Lang.tr("admin.war.title")));
        int slot = 0;
        for (dev.hearthold.clan.Clan c : clans.all()) {
            if (c.id().equals(own.id()) || slot >= 45) {
                continue;
            }
            boolean base = territory != null && territory.heartOf(c.id()).isPresent();
            boolean atWar = wars.warBetween(own.id(), c.id()).isPresent();
            m.set(slot++, Menu.icon(atWar ? Material.RED_BANNER : Material.WHITE_BANNER, c.name(),
                    Lang.tr(base ? "admin.war.has-base" : "admin.war.no-base"), Lang.tr(atWar ? "admin.war.at-war" : "admin.war.click")), p -> {
                p.closeInventory();
                try {
                    wars.forceDeclare(own, c);
                } catch (dev.hearthold.clan.ClanException e) {
                    p.sendMessage(Component.text(e.getMessage(), RED));
                }
            });
        }
        if (slot == 0) {
            m.set(22, Menu.icon(Material.PAPER, Lang.tr("admin.war.no-clans"), Lang.tr("admin.war.no-clans.lore")), null);
        }
        m.set(49, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> testMenu(p, back));
        m.open(player);
    }

    private void hudPicker(Player player, Consumer<Player> back) {
        Menu m = new Menu(6, Component.text(Lang.tr("admin.test.hud")));
        int slot = 0;
        for (dev.hearthold.clan.Clan c : clans.all()) {
            if (slot >= 45) {
                break;
            }
            m.set(slot++, Menu.icon(Material.WHITE_BANNER, c.name(), Lang.tr("admin.members", c.members().size()),
                    Lang.tr("admin.hud.click")), p -> {
                hud.setViewAs(p.getUniqueId(), c.id());
                testMenu(p, back);
            });
        }
        m.set(49, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> testMenu(p, back));
        m.open(player);
    }

    private static void give(Player p, org.bukkit.inventory.ItemStack item) {
        p.getInventory().addItem(item).values().forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
    }

    /** A 5×5 room: floor, table in the centre, two rows of bookshelves around (no corners) and an entrance on the player's side. */
    private void buildEnchantRoom(Player p) {
        org.bukkit.block.BlockFace face = p.getFacing();
        org.bukkit.block.Block center = p.getLocation().getBlock().getRelative(face, 3);
        int ex = -face.getModX() * 2;
        int ez = -face.getModZ() * 2;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                center.getRelative(dx, -1, dz).setType(Material.STONE_BRICKS);
                boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                boolean entrance = dx == ex && dz == ez;
                for (int dy = 0; dy <= 2; dy++) {
                    Material type = ring && !corner && !entrance && dy <= 1 ? Material.BOOKSHELF : Material.AIR;
                    center.getRelative(dx, dy, dz).setType(type);
                }
            }
        }
        center.setType(Material.ENCHANTING_TABLE);
        p.setLevel(Math.max(p.getLevel(), 30));
        give(p, new org.bukkit.inventory.ItemStack(Material.LAPIS_LAZULI, 64));
        String station = "";
        if (stations != null && territory != null) {
            var clan = clans.ofPlayer(p.getUniqueId())
                    .filter(c -> territory.claimOf(center).map(dev.hearthold.clan.Clan::id).filter(c.id()::equals).isPresent());
            if (clan.isPresent()) {
                stations.register(center, clan.get().id(), dev.hearthold.craft.StationService.Type.ENCHANTING);
                station = Lang.tr("admin.enchant.station");
            } else {
                station = Lang.tr("admin.enchant.not-station");
            }
        }
        p.sendMessage(Component.text(Lang.tr("admin.enchant.built", station),
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

    private void confirmDeleteClan(Player player, dev.hearthold.clan.Clan clan, Consumer<Player> back) {
        Menu m = new Menu(3, Component.text(Lang.tr("admin.delete.title", clan.name())));
        long spheres = territory == null ? 0 : territory.spheresOf(clan.id()).size();
        long cols = collectors == null ? 0 : collectors.countOf(clan.id());
        m.set(4, Menu.icon(Material.PAPER, Lang.tr("admin.delete.summary"), Lang.tr("admin.members", clan.members().size()),
                Lang.tr("admin.delete.spheres", spheres), Lang.tr("admin.collectors", cols), Lang.tr("admin.delete.rest")), null);
        m.set(11, Menu.icon(Material.RED_CONCRETE, Lang.tr("admin.delete.confirm"), Lang.tr("admin.delete.irreversible")), p -> {
            p.closeInventory();
            deleteClan(p, clan);
        });
        m.set(15, Menu.icon(Material.LIME_CONCRETE, Lang.tr("admin.cancel")), p -> testMenu(p, back));
        m.open(player);
    }

    /** Deletes a clan together with its sphere and collector blocks. Returns true if deleted. */
    private boolean deleteClan(Player p, dev.hearthold.clan.Clan clan) {
        int blocks = 0;
        if (territory != null) {
            for (dev.hearthold.base.Sphere s : territory.spheresOf(clan.id())) {
                blocks += clearBlock(s.world(), s.x(), s.y(), s.z());
            }
        }
        if (collectors != null) {
            for (dev.hearthold.collector.Collector col : List.copyOf(collectors.all())) {
                if (col.clanId().equals(clan.id())) {
                    blocks += clearBlock(col.world(), col.x(), col.y(), col.z());
                    collectors.remove(col);
                }
            }
        }
        try {
            clans.forceDisband(clan);
        } catch (dev.hearthold.clan.ClanException e) {
            p.sendMessage(Component.text(e.getMessage(), RED));
            return false;
        }
        p.sendMessage(Component.text(Lang.tr("admin.delete.done", clan.name(), blocks), GREEN));
        return true;
    }

    /** Removes a core block without dropping its contents. Returns 1 if there was a block. */
    private static int clearBlock(String worldName, int x, int y, int z) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            return 0;
        }
        org.bukkit.block.Block b = world.getBlockAt(x, y, z);
        if (b.getState() instanceof org.bukkit.inventory.InventoryHolder holder) {
            holder.getInventory().clear();
        }
        boolean had = !b.getType().isAir();
        b.setType(Material.AIR, false);
        return had ? 1 : 0;
    }

    /** A 3×3×2 farm in front of the player: floor, farmland with water in the centre, various crops on it. */
    private void buildFarm(Player p) {
        org.bukkit.block.BlockFace face = p.getFacing();
        org.bukkit.block.Block feet = p.getLocation().getBlock();
        org.bukkit.block.Block center = feet.getRelative(face, 3).getRelative(org.bukkit.block.BlockFace.DOWN);
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                org.bukkit.block.Block top = center.getRelative(dx, 0, dz);
                top.getRelative(org.bukkit.block.BlockFace.DOWN).setType(Material.STONE_BRICKS);
                org.bukkit.block.Block above = top.getRelative(org.bukkit.block.BlockFace.UP);
                if (dx == 0 && dz == 0) {
                    top.setType(Material.WATER);
                    above.setType(Material.AIR);
                    continue;
                }
                top.setType(Material.FARMLAND);
                if (top.getBlockData() instanceof org.bukkit.block.data.type.Farmland f) {
                    f.setMoisture(f.getMaximumMoisture());
                    top.setBlockData(f);
                }
                above.setType(FARM_CROPS[i++ % FARM_CROPS.length]);
                above.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR);
            }
        }
        p.sendMessage(Component.text(Lang.tr("admin.farm.built"), net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

    private void fastForwardMenu(Player player, Consumer<Player> back) {
        Menu m = new Menu(3, Component.text(Lang.tr("admin.test.fast-forward")));
        m.set(11, Menu.icon(Material.LIME_CONCRETE, Lang.tr("admin.fast-forward.own"), Lang.tr("admin.fast-forward.own.lore")), p -> {
            clans.ofPlayer(p.getUniqueId()).ifPresentOrElse(c -> fastForward(p, c),
                    () -> p.sendActionBar(Component.text(Lang.tr("admin.not-in-clan"))));
        });
        m.set(15, Menu.icon(Material.WHITE_BANNER, Lang.tr("admin.fast-forward.pick"), Lang.tr("admin.fast-forward.pick.lore")),
                p -> clanPicker(p, back));
        m.set(22, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> testMenu(p, back));
        m.open(player);
    }

    private void clanPicker(Player player, Consumer<Player> back) {
        Menu m = new Menu(6, Component.text(Lang.tr("admin.fast-forward.pick-title")));
        int slot = 0;
        for (dev.hearthold.clan.Clan c : clans.all()) {
            if (slot >= 45) {
                break;
            }
            long count = collectors == null ? 0 : collectors.countOf(c.id());
            m.set(slot++, Menu.icon(Material.WHITE_BANNER, c.name(), Lang.tr("admin.collectors", count), Lang.tr("admin.fast-forward.click")),
                    p -> fastForward(p, c));
        }
        m.set(53, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> fastForwardMenu(p, back));
        m.open(player);
    }

    private void fastForward(Player p, dev.hearthold.clan.Clan clan) {
        p.closeInventory();
        if (collectors == null) {
            return;
        }
        int[] r = collectors.fastForward(clan.id(), 1.0);
        p.sendMessage(Component.text(Lang.tr("admin.fast-forward.done", clan.name(), r[0], r[1]),
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

    private void eventsMenu(Player player, Consumer<Player> back) {
        Menu m = new Menu(6, Component.text(Lang.tr("admin.events")));
        int slot = 0;
        for (EventService.Site s : events.all()) {
            if (slot >= 45) {
                break;
            }
            boolean wave = s.type() == EventService.Type.WAVE;
            m.set(slot++, Menu.icon(wave ? Material.BLAZE_ROD : Material.CHEST, "«" + s.name() + "»",
                    Lang.tr("admin.events.entry", Lang.tr(wave ? "admin.events.waves" : "admin.events.chests"), s.state().title()), s.coords(),
                    Lang.tr("admin.events.click")), p -> eventCard(p, s, back));
        }
        if (slot == 0) {
            m.set(22, Menu.icon(Material.PAPER, Lang.tr("admin.events.none"), Lang.tr("admin.events.none.lore")), null);
        }
        if (prompt != null) {
            m.set(49, Menu.icon(Material.LIME_DYE, Lang.tr("admin.events.add"), Lang.tr("admin.events.add.lore1"),
                    Lang.tr("admin.events.add.lore2")), p -> eventTypePicker(p, back));
        }
        m.set(53, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> open(p, back));
        m.open(player);
    }

    private ChatPrompt prompt;

    public void setPrompt(ChatPrompt prompt) {
        this.prompt = prompt;
    }

    private void eventCard(Player player, EventService.Site s, Consumer<Player> back) {
        boolean wave = s.type() == EventService.Type.WAVE;
        Menu m = new Menu(3, Component.text(Lang.tr("admin.events.card-title", s.name())));
        m.set(11, Menu.icon(Material.LIME_CONCRETE, Lang.tr(wave ? "admin.events.start-waves" : "admin.events.start-chests")),
                run("event start " + s.name()));
        m.set(13, Menu.icon(Material.COMPASS, Lang.tr("admin.events.compass")), run("event compass " + s.name()));
        m.set(15, Menu.icon(Material.BARRIER, Lang.tr("admin.events.remove"), Lang.tr("admin.next-confirm")),
                p -> ActionsMenu.confirm(p, Lang.tr("admin.events.remove-title", s.name()), List.of(Lang.tr("admin.events.remove-irreversible")),
                        run("event remove " + s.name()), q -> eventsMenu(q, back)));
        m.set(18, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> eventsMenu(p, back));
        m.open(player);
    }

    private void eventTypePicker(Player player, Consumer<Player> back) {
        Menu m = new Menu(3, Component.text(Lang.tr("admin.events.type-title")));
        m.set(11, Menu.icon(Material.BLAZE_ROD, Lang.tr("admin.events.waves"), Lang.tr("admin.events.waves.lore")), p -> askEvent(p, "wave"));
        m.set(15, Menu.icon(Material.CHEST, Lang.tr("admin.events.chests"), Lang.tr("admin.events.chests.lore")), p -> askEvent(p, "chest"));
        m.set(18, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> eventsMenu(p, back));
        m.open(player);
    }

    private void askEvent(Player player, String type) {
        prompt.ask(player, Lang.tr("admin.events.ask"), Lang.tr("admin.events.ask-hint"), (p, text) -> {
            String[] parts = text.trim().split("\s+");
            p.performCommand("event add " + type + " " + parts[0] + (parts.length > 1 ? " " + parts[1] : ""));
        });
    }

    /** Changes any clan's upkeep by an arbitrary amount. */
    private void upkeepClanPicker(Player player, Consumer<Player> back) {
        Menu m = new Menu(6, Component.text(Lang.tr("admin.upkeep.pick-title")));
        int slot = 0;
        for (dev.hearthold.clan.Clan c : clans.all()) {
            if (slot >= 45) {
                break;
            }
            m.set(slot++, Menu.icon(Material.WHITE_BANNER, c.name(), Lang.tr("admin.upkeep.click")),
                    p -> upkeepAmount(p, c, back));
        }
        m.set(49, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> open(p, back));
        m.open(player);
    }

    private void upkeepAmount(Player player, dev.hearthold.clan.Clan c, Consumer<Player> back) {
        Menu m = new Menu(3, Component.text(Lang.tr("admin.upkeep.title", c.name())));
        int[] amounts = {-100, -10, -1, 1, 10, 100};
        int slot = 10;
        for (int a : amounts) {
            m.set(slot++, Menu.icon(a < 0 ? Material.REDSTONE : Material.EMERALD, Math.min(64, Math.abs(a)),
                    (a > 0 ? "+" : "") + a), p -> {
                p.performCommand("hearthold upkeep " + c.name() + " " + a);
                upkeepAmount(p, c, back);
            });
            if (slot == 13) {
                slot++;
            }
        }
        if (prompt != null) {
            m.set(13, Menu.icon(Material.NAME_TAG, Lang.tr("admin.upkeep.enter"), Lang.tr("admin.upkeep.enter.lore")),
                    p -> prompt.ask(p, Lang.tr("admin.upkeep.ask", c.name()), Lang.tr("admin.upkeep.ask-hint"),
                            (q, text) -> q.performCommand("hearthold upkeep " + c.name() + " " + text.replaceAll("\s+", ""))));
        }
        m.set(18, Menu.icon(Material.ARROW, Lang.tr("admin.back")), p -> upkeepClanPicker(p, back));
        m.open(player);
    }

    private static Consumer<Player> run(String command) {
        return p -> {
            p.closeInventory();
            p.performCommand(command);
        };
    }
}
