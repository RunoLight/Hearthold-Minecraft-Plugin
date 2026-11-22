package dev.hearthold;

import dev.hearthold.base.BaseItems;
import dev.hearthold.base.BaseLevelService;
import dev.hearthold.body.BodyListener;
import dev.hearthold.body.BodyService;
import dev.hearthold.body.CompassMenu;
import dev.hearthold.build.BuildListener;
import dev.hearthold.base.DefenderBonusTask;
import dev.hearthold.base.Sphere;
import dev.hearthold.base.SphereListener;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.AllyService;
import dev.hearthold.clan.ClanCommand;
import dev.hearthold.clan.ClanService;
import dev.hearthold.collector.CollectorListener;
import dev.hearthold.collector.CollectorService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.craft.CraftListener;
import dev.hearthold.craft.StationService;
import dev.hearthold.economy.BuyerListener;
import dev.hearthold.economy.ChunkEnergyService;
import dev.hearthold.economy.DeathListener;
import dev.hearthold.economy.EconomyService;
import dev.hearthold.economy.EnergyCell;
import dev.hearthold.economy.GrowthListener;
import dev.hearthold.economy.HeartInteractListener;
import dev.hearthold.i18n.Lang;
import dev.hearthold.health.BlockHealthListener;
import dev.hearthold.health.BlockHealthService;
import dev.hearthold.health.HealthDisplay;
import dev.hearthold.health.PrimeTimeClock;
import dev.hearthold.event.EventCommand;
import dev.hearthold.event.EventListener;
import dev.hearthold.event.EventService;
import dev.hearthold.map.MapService;
import dev.hearthold.newbie.NewbieListener;
import dev.hearthold.newbie.NewbieService;
import dev.hearthold.plot.PlotCommand;
import dev.hearthold.plot.PlotListener;
import dev.hearthold.plot.PlotService;
import dev.hearthold.map.OreRespawnTask;
import dev.hearthold.storage.Database;
import dev.hearthold.trade.CaravanListener;
import dev.hearthold.trade.CaravanLog;
import dev.hearthold.trade.CaravanService;
import dev.hearthold.trade.Stable;
import dev.hearthold.trade.Suitcase;
import dev.hearthold.trade.TradeCommand;
import dev.hearthold.trade.TradeService;
import dev.hearthold.trade.Treasury;
import dev.hearthold.trade.TreasuryMenu;
import dev.hearthold.ui.AdminMenu;
import dev.hearthold.ui.CompassService;
import dev.hearthold.ui.HudService;
import dev.hearthold.ui.HeartMenu;
import dev.hearthold.ui.HeartTreasury;
import dev.hearthold.ui.ClanGuide;
import dev.hearthold.collector.ChunkResourceService;
import dev.hearthold.ui.Menu;
import org.bukkit.entity.Player;
import dev.hearthold.ui.WeightMenu;
import dev.hearthold.web.ScoutingService;
import dev.hearthold.web.SiteCommand;
import dev.hearthold.web.WebData;
import dev.hearthold.web.WebServer;
import dev.hearthold.web.AdminWebData;
import dev.hearthold.weight.StorageListener;
import dev.hearthold.weight.WeightCommand;
import dev.hearthold.weight.WeightListener;
import dev.hearthold.weight.WeightService;
import dev.hearthold.war.CaptureTask;
import dev.hearthold.war.VassalService;
import dev.hearthold.war.WarCommand;
import dev.hearthold.war.WarService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;

public final class HeartholdPlugin extends JavaPlugin {

    /** How often block HP and chunk energy are flushed to the database: every 5 minutes. */
    private static final long HEALTH_SAVE_TICKS = 20L * 60 * 5;

    private Database database;
    private HeartholdConfig config;
    private ClanService clans;
    private TerritoryService territory;
    private BlockHealthService blockHealth;
    private HealthDisplay healthDisplay;
    private PrimeTimeClock primeTime;
    private EconomyService economy;
    private ChunkEnergyService chunkEnergy;
    private BaseLevelService baseLevels;
    private BuyerListener buyer;
    private CollectorService collectors;
    private MapService map;
    private OreRespawnTask oreRespawn;
    private WeightService weights;
    private CaptureTask capture;
    private StationService stations;
    private EventService events;
    private WebServer web;
    private AdminMenu adminMenu;
    private dev.hearthold.base.HeartRemovalService heartRemoval;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dev.hearthold.i18n.Lang.load(this, getConfig().getString("language", dev.hearthold.i18n.Lang.DEFAULT));
        config = HeartholdConfig.load(getConfig(), getLogger());

        try {
            database = Database.open(getDataFolder().toPath().resolve("hearthold.db"));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to open the database, disabling the plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        clans = new ClanService(database, getLogger());
        clans.loadAll();
        AllyService allies = new AllyService(database, clans);
        allies.loadAll();

        BaseItems baseItems = new BaseItems(this);
        territory = new TerritoryService(database, clans, () -> config);
        territory.loadAll();
        NewbieService newbie = new NewbieService(database, clans, territory, () -> config, getLogger());
        newbie.loadAll();
        territory.setRadiusLimit(newbie::radiusLimit);
        territory.setPlacementCheck((clan, kind) -> kind == dev.hearthold.base.Sphere.Kind.NODE
                && newbie.isProtected(clan.id()) ? Lang.tr("misc.newbie.no-nodes") : null);

        economy = new EconomyService(database, clans, territory, () -> config, getLogger());
        economy.loadAll();
        // A clan without members but with a base remains (abandoned base); the heart is removed on a timer.
        clans.setKeepWhenEmpty(c -> territory.heartOf(c.id()).isPresent());
        heartRemoval = new dev.hearthold.base.HeartRemovalService(database, clans, territory, () -> config, getLogger());
        heartRemoval.loadAll();
        heartRemoval.setProtection(economy::isProtected);
        baseLevels = new BaseLevelService(database, clans, territory, economy, () -> config, getLogger());
        baseLevels.loadAll();
        chunkEnergy = new ChunkEnergyService(() -> config);
        try {
            chunkEnergy.loadAll(database);
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to load chunk energy", e);
        }
        EnergyCell cells = new EnergyCell(this);
        buyer = new BuyerListener(this, database, () -> config, cells, baseItems);
        buyer.loadAll();

        collectors = new CollectorService(database, clans, territory, economy, () -> config, getLogger());
        collectors.loadAll();
        CollectorListener collectorListener = new CollectorListener(this, collectors, clans, () -> config);
        map = new MapService(this, () -> config);
        map.applyBorders();
        oreRespawn = new OreRespawnTask(() -> config);

        weights = new WeightService(this, () -> config);
        weights.registerBrewing();
        WeightListener weightListener = new WeightListener(this, weights, () -> config);

        Treasury treasury = new Treasury(database, clans, getLogger());
        treasury.loadAll();
        TradeService trade = new TradeService(database, clans, getLogger());
        trade.loadAll();
        CaravanLog caravanLog = new CaravanLog(database, getLogger());
        Stable stable = new Stable(this, database, clans);
        stable.loadAll();
        Suitcase suitcase = new Suitcase(this);
        CaravanService caravans = new CaravanService(clans, trade, treasury, suitcase, caravanLog, weights, () -> config);
        weights.setExtraWeight(caravans::cargoWeight);
        TreasuryMenu treasuryMenu = new TreasuryMenu(treasury, caravanLog);
        CaravanListener caravanListener = new CaravanListener(suitcase, caravans, stable, caravanLog, territory, clans,
                () -> config);

        healthDisplay = new HealthDisplay(this, () -> config);
        blockHealth = new BlockHealthService(() -> config);
        try {
            blockHealth.loadAll(database);
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to load block HP, all blocks are treated as intact", e);
        }
        primeTime = new PrimeTimeClock(() -> config);

        var pm = getServer().getPluginManager();
        PlotService plots = new PlotService(database, clans, territory, () -> config, getLogger());
        plots.loadAll();
        dev.hearthold.plot.ChestAccess chestAccess = new dev.hearthold.plot.ChestAccess(database, getLogger());
        chestAccess.loadAll();
        PlotListener plotListener = new PlotListener(this, plots, territory, clans, chestAccess, () -> config);
        plotListener.setExempt(b -> collectors.at(b).isPresent());
        dev.hearthold.clan.Clan.setHomeLookup(plots::homeOf);
        pm.registerEvents(plotListener, this);
        economy.setEnergyBonus(id -> plots.prestige(id) * config.plot().energyPerPrestige());
        BuildListener buildListener = new BuildListener(territory, baseItems, () -> config);
        buildListener.setFastBuild(plotListener::isTrusted);
        pm.registerEvents(buildListener, this);
        // Wars and vassalage.
        WarService wars = new WarService(database, clans, allies, territory, () -> config, getLogger());
        buildListener.setAtWarWith((p, owner) -> clans.ofPlayer(p.getUniqueId())
                .map(own -> wars.atWar(own.id(), owner.id())).orElse(false));
        wars.loadAll();
        Treasury tributeTreasury = new Treasury(database, "tribute_treasury", getLogger());
        tributeTreasury.loadAll();
        VassalService vassals = new VassalService(database, clans, treasury, tributeTreasury, () -> config, getLogger());
        vassals.loadAll();
        wars.setVassals(vassals);
        capture = new CaptureTask(wars, vassals, territory, clans, primeTime, () -> config);
        capture.setProtection(economy::isProtected);
        capture.setAllies(allies::areAllies);
        capture.setImmune(id -> newbie.isProtected(id) || wars.isShielded(id));
        heartRemoval.setSuzerainCheck((a, b) -> vassals.suzerainOf(b).map(v -> v.suzerain().equals(a)).orElse(false));
        economy.setCostMultiplier(wars::upkeepMultiplier);
        blockHealth.setRegenMultiplier(k -> {
            java.util.UUID owner = territory.activeClaimAt(k.world(), k.x(), k.y(), k.z());
            if (owner == null) {
                // The zone of a still-activating sphere already belongs to the clan, but there is no regeneration there.
                return territory.claimAt(k.world(), k.x(), k.y(), k.z()) != null ? 0.0 : 1.0;
            }
            return wars.regenMultiplier(owner);
        });
        collectors.setTribute(vassals::divertCollector);
        caravans.setTribute(vassals::divertTrade, tributeTreasury, vassals::isVassalage);

        SphereListener sphereListener = new SphereListener(baseItems, territory, clans);
        sphereListener.onHeartPlaced((clan, item) -> baseItems.patronOf(item).ifPresent(patronId -> {
            var patron = clans.byId(patronId);
            if (patron.isEmpty() || patronId.equals(clan.id())) {
                return;
            }
            vassals.create(patron.get(), clan, VassalService.Kind.PATRONAGE, config.war().patronageDays());
        }));
        sphereListener.onHeartPlaced((clan, item) -> newbie.onHeartPlaced(clan));
        sphereListener.onProxyPlaced(clan -> newbie.end(clan, Lang.tr("misc.newbie.reason.second-base")));
        wars.setNewbieHooks(newbie::isProtected, clan -> newbie.end(clan, Lang.tr("misc.newbie.reason.declared-war")));
        pm.registerEvents(sphereListener, this);
        pm.registerEvents(new NewbieListener(newbie, territory, () -> config), this);

        // Offline player bodies and the compass.
        BodyService bodies = new BodyService(database, getLogger());
        bodies.loadAll();
        CompassMenu compassMenu = new CompassMenu(clans, territory, plots::prestige, trade::reputation, id -> {
            String removalNotice = heartRemoval.notice(id);
            if (removalNotice != null) {
                return removalNotice;
            }
            if (newbie.isProtected(id)) {
                return Lang.tr("misc.compass.newbie-protected");
            }
            if (wars.isShielded(id)) {
                return Lang.tr("misc.compass.siege-shield");
            }
            return vassals.suzerainOf(id).map(v -> Lang.tr("misc.compass.vassal-of", v.suzerainName())).orElse(null);
        });
        pm.registerEvents(compassMenu, this);
        events = new EventService(this, database, () -> config);
        events.loadAll();
        pm.registerEvents(new EventListener(events), this);
        BodyListener bodyListener = new BodyListener(bodies, territory, clans, () -> config, suitcase::is);
        pm.registerEvents(bodyListener, this);

        // UI: menus, sidebar, compass.
        pm.registerEvents(new Menu.Listen(), this);
        HudService hud = new HudService(clans, territory, plots, weights, () -> config, chunkEnergy, collectors,
                primeTime);
        hud.setLevels(baseLevels);
        hud.setBaseNotice(heartRemoval::notice);
        hud.setRelation((own, other) -> {
            var war = wars.warBetween(own, other);
            String rel = war.isPresent()
                    ? Lang.tr(war.get().isActive(System.currentTimeMillis()) ? "misc.relation.war" : "misc.relation.war-preparing")
                    : Lang.tr(allies.areAllies(own, other) ? "misc.relation.alliance" : "misc.relation.neutral");
            if (vassals.suzerainOf(other).filter(v -> v.suzerain().equals(own)).isPresent()) {
                rel += Lang.tr("misc.relation.your-vassal");
            } else if (vassals.suzerainOf(own).filter(v -> v.suzerain().equals(other)).isPresent()) {
                rel += Lang.tr("misc.relation.your-suzerain");
            }
            boolean trading = trade.routesOf(own).stream().anyMatch(r -> r.status() == dev.hearthold.trade.TradeRoute.Status.ACTIVE
                    && r.partnerOf(own).equals(other));
            return rel + Lang.tr(trading ? "misc.relation.trade-yes" : "misc.relation.trade-no");
        });
        pm.registerEvents(hud, this);
        ChunkResourceService chunkResources = new ChunkResourceService(database, getLogger());
        chunkResources.loadAll();
        collectors.setChunkResources(chunkResources);
        adminMenu = new AdminMenu(clans, events, collectorListener.guide(), chunkEnergy, chunkResources, () -> config);
        HeartTreasury heartTreasury = new HeartTreasury(economy);
        pm.registerEvents(heartTreasury, this);
        HeartMenu heartMenu = new HeartMenu(clans, territory, economy, newbie, heartTreasury, cells,
                collectorListener.guide(), () -> config);
        ClanGuide clanGuide = new ClanGuide(clans, territory);
        dev.hearthold.ui.CollectorsMenu collectorsMenu = new dev.hearthold.ui.CollectorsMenu(collectors,
                collectorListener.guide(), clans, () -> config);
        heartMenu.setCollectorsMenu(collectorsMenu);
        heartMenu.setLevels(baseLevels);
        heartMenu.setRemoval(heartRemoval);
        adminMenu.setLevels(baseLevels);
        adminMenu.setCollectors(collectors);
        adminMenu.setTerritory(territory);
        adminMenu.setNewbie(newbie);
        adminMenu.setTesting(wars, economy, bodyListener, weights, blockHealth, hud);
        adminMenu.setCapture(capture);
        clans.onJoin((clan, playerId) -> {
            Player joined = getServer().getPlayer(playerId);
            if (joined != null) {
                // One tick later: the /clan accept messages go out first.
                getServer().getScheduler().runTask(this, () -> clanGuide.onJoined(joined, clan));
            }
        });
        CompassService compass = new CompassService(this, bodyListener, compassMenu, new WeightMenu(weights, () -> config),
                adminMenu, clanGuide, heartMenu, collectorListener.guide());
        compass.setCollectorsMenu(collectorsMenu);
        dev.hearthold.ui.ChatPrompt chatPrompt = new dev.hearthold.ui.ChatPrompt(this);
        pm.registerEvents(chatPrompt, this);
        dev.hearthold.ui.ActionsMenu actionsMenu = new dev.hearthold.ui.ActionsMenu(clans, allies, territory, wars, trade,
                stable, plots, events, chatPrompt, () -> config);
        compass.setActions(actionsMenu);
        actionsMenu.setClanStatus(heartRemoval::notice);
        actionsMenu.setPlotListener(plotListener);
        plotListener.onHeartClick((p, plot) -> actionsMenu.plotCard(p, plot, q -> actionsMenu.plotMenu(q, org.bukkit.entity.HumanEntity::closeInventory)));
        plotListener.onChestSettings(actionsMenu::chestMenu);
        heartMenu.setActions(actionsMenu);
        adminMenu.setPrompt(chatPrompt);
        pm.registerEvents(compass, this);
        Objects.requireNonNull(getCommand("compass")).setExecutor(compass);
        BlockHealthListener blockHealthListener =
                new BlockHealthListener(blockHealth, territory, healthDisplay, primeTime, () -> config);
        blockHealthListener.setClanLookup(p -> clans.ofPlayer(p).map(c -> c.id()).orElse(null));
        blockHealthListener.setSiegeRule(wars::siegeDenial);
        blockHealthListener.setFreeBreak(plotListener::isTrusted);
        blockHealthListener.setDamagePopup(new dev.hearthold.health.DamagePopup(this));
        blockHealthListener.setProtectedHp(baseLevels::maxHp);
        pm.registerEvents(blockHealthListener, this);
        dev.hearthold.health.BlockInspectListener inspect = new dev.hearthold.health.BlockInspectListener(this, blockHealth,
                territory, clans, healthDisplay, primeTime, () -> config);
        inspect.setProtectedHp(baseLevels::maxHp);
        pm.registerEvents(inspect, this);

        stations = new StationService(database, clans, getLogger());
        stations.loadAll();
        adminMenu.setStations(stations);
        pm.registerEvents(new CraftListener(this, stations, territory, clans, () -> config), this);
        HeartInteractListener heartListener = new HeartInteractListener(territory, clans, economy, chunkEnergy, cells,
                baseItems, () -> config);
        heartListener.setSphereMenu(heartMenu::handleSphereClick);
        pm.registerEvents(heartListener, this);
        pm.registerEvents(new GrowthListener(chunkEnergy, () -> config), this);
        pm.registerEvents(buyer, this);
        pm.registerEvents(new DeathListener(() -> config, suitcase::is), this);
        pm.registerEvents(treasuryMenu, this);
        pm.registerEvents(caravanListener, this);
        pm.registerEvents(collectorListener, this);
        pm.registerEvents(weightListener, this);
        pm.registerEvents(new StorageListener(() -> config), this);

        ClanCommand clanCommand = new ClanCommand(clans, allies, territory, economy, baseItems, collectorListener, () -> config);
        clanCommand.setNewbie(newbie);
        clanCommand.setCollectors(collectors);
        PluginCommand clan = Objects.requireNonNull(getCommand("clan"));
        clan.setExecutor(clanCommand);
        clan.setTabCompleter(clanCommand);

        Objects.requireNonNull(getCommand("weight")).setExecutor(new WeightCommand(weights, () -> config));

        TradeCommand tradeCommand = new TradeCommand(clans, trade, treasury, treasuryMenu, caravans, stable, caravanLog,
                territory, () -> config);
        PluginCommand tradeCmd = Objects.requireNonNull(getCommand("trade"));
        tradeCmd.setExecutor(tradeCommand);
        tradeCmd.setTabCompleter(tradeCommand);

        WarCommand warCommand = new WarCommand(clans, wars, vassals, capture, caravans, economy, territory, baseItems,
                () -> config);
        PluginCommand warCmd = Objects.requireNonNull(getCommand("war"));
        warCmd.setExecutor(warCommand);
        warCmd.setTabCompleter(warCommand);

        PlotCommand plotCommand = new PlotCommand(plots, plotListener, clans);
        PluginCommand plotCmd = Objects.requireNonNull(getCommand("plot"));
        plotCmd.setExecutor(plotCommand);
        plotCmd.setTabCompleter(plotCommand);

        EventCommand eventCommand = new EventCommand(events);
        PluginCommand eventCmd = Objects.requireNonNull(getCommand("event"));
        eventCmd.setExecutor(eventCommand);
        eventCmd.setTabCompleter(eventCommand);

        // Web portal and scouting.
        ScoutingService scouting = new ScoutingService(database, clans, () -> config, getLogger());
        scouting.loadAll();
        economy.setExtraCost(id -> scouting.dailyCost(id) + baseLevels.upkeepPerDay(id));
        compass.setScoutingMenu(new dev.hearthold.ui.ScoutingMenu(scouting, clans, () -> config));
        WebData webData = new WebData(clans, territory, events, wars, vassals, trade, plots, scouting, suitcase::is,
                () -> config, primeTime);
        webData.setBaseNotice(heartRemoval::notice);
        if (config.web().enabled()) {
            try {
                web = new WebServer(this, webData, scouting, () -> config);
                web.start();
                scheduler().runTaskTimer(this, webData::refresh, 20L, 100L);
                AdminWebData adminWeb = new AdminWebData(clans, economy, wars, trade, treasury, () -> config);
                adminWeb.setLevels(baseLevels);
                adminWeb.setFastCapture(capture::isFast);
                adminWeb.setBaseNotice(heartRemoval::notice);
                web.setAdmin(adminWeb);
                scheduler().runTaskTimer(this, adminWeb::refresh, 30L, 600L);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to start the web portal", e);
                web = null;
            }
        }
        SiteCommand siteCommand = new SiteCommand(web, scouting, clans, () -> config);
        PluginCommand siteCmd = Objects.requireNonNull(getCommand("site"));
        siteCmd.setExecutor(siteCommand);
        siteCmd.setTabCompleter(siteCommand);

        AdminCommand admin = new AdminCommand(this);
        PluginCommand adminCmd = Objects.requireNonNull(getCommand("hearthold"));
        adminCmd.setExecutor(admin);
        adminCmd.setTabCompleter(admin);

        var scheduler = getServer().getScheduler();
        // HP regeneration once per second.
        scheduler.runTaskTimer(this, blockHealth::tickRegen, 20L, 20L);
        scheduler.runTaskTimer(this, this::saveState, HEALTH_SAVE_TICKS, HEALTH_SAVE_TICKS);
        scheduler.runTaskTimer(this, economy::tick, 1200L, 1200L);
        long collectorTicks = 20L * 60 * config.map().collector().intervalMinutes();
        scheduler.runTaskTimer(this, collectors::tick, collectorTicks, collectorTicks);
        scheduler.runTaskTimer(this, oreRespawn, 1200L, 1200L);
        scheduler.runTaskTimer(this, weightListener::tick, 10L, 10L);
        scheduler.runTaskTimer(this, caravanListener::tick, 20L, 20L);
        scheduler.runTaskTimer(this, trade::tick, 1200L, 1200L);
        scheduler.runTaskTimer(this, newbie::tick, 1200L, 1200L);
        scheduler.runTaskTimer(this, stations::tickDisplays, 20L, 20L);
        scheduler.runTaskTimer(this, events::tick, 20L, 20L);
        scheduler.runTaskTimer(this, hud::tick, 10L, 10L);
        scheduler.runTaskTimer(this, new dev.hearthold.health.DamageHighlightTask(blockHealth, territory, clans), 40L, 20L);
        scheduler.runTaskTimer(this, () -> {
            wars.tick();
            vassals.tick();
            capture.run();
        }, 20L, 20L);
        scheduler.runTaskTimer(this, this::announceActivations, 100L, 100L);
        scheduler.runTaskTimer(this, heartRemoval::tick, 60L, 100L);
        scheduler.runTaskTimer(this, new DefenderBonusTask(territory, clans, allies, () -> config),
                DefenderBonusTask.PERIOD_TICKS, DefenderBonusTask.PERIOD_TICKS);

        getLogger().info("Hearthold enabled: " + clans.all().size() + " clans, " + territory.spheres().size()
                + " spheres, " + blockHealth.trackedCount() + " damaged blocks");
    }

    @Override
    public void onDisable() {
        if (weights != null) {
            weights.unregisterBrewing();
        }
        if (map != null && map.isRunning()) {
            map.stop();
        }
        if (healthDisplay != null) {
            healthDisplay.clearAll();
        }
        if (capture != null) {
            capture.hideAll();
        }
        if (heartRemoval != null) {
            heartRemoval.removeAllDisplays();
        }
        if (stations != null) {
            stations.clearDisplays();
        }
        if (events != null) {
            events.shutdown();
        }
        if (web != null) {
            web.stop();
        }
        if (database != null) {
            saveState();
            database.close();
        }
    }

    /** Periodic save of in-memory state: block HP, chunk energy, clan balances. */
    private void saveState() {
        try {
            if (blockHealth != null) {
                blockHealth.saveAll(database);
            }
            if (chunkEnergy != null) {
                chunkEnergy.saveAll(database);
            }
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to save state", e);
        }
        if (economy != null) {
            economy.saveAll();
        }
        if (collectors != null) {
            collectors.saveBuffers();
        }
    }

    private void announceActivations() {
        for (Sphere s : territory.pollActivated()) {
            clans.byId(s.clanId()).ifPresent(c -> ClanService.broadcast(c,
                    Component.text(Lang.tr("misc.sphere-activated", s.describe()), NamedTextColor.GREEN)));
        }
    }

    public void reloadHeartholdConfig() {
        reloadConfig();
        dev.hearthold.i18n.Lang.load(this, getConfig().getString("language", dev.hearthold.i18n.Lang.DEFAULT));
        config = HeartholdConfig.load(getConfig(), getLogger());
        map.applyBorders();
        weights.registerBrewing();
    }

    public HeartholdConfig heartholdConfig() {
        return config;
    }

    public BlockHealthService blockHealth() {
        return blockHealth;
    }

    public TerritoryService territory() {
        return territory;
    }

    public PrimeTimeClock primeTime() {
        return primeTime;
    }

    public EconomyService economy() {
        return economy;
    }

    public BaseLevelService baseLevels() {
        return baseLevels;
    }

    public CollectorService collectors() {
        return collectors;
    }

    public ChunkEnergyService chunkEnergy() {
        return chunkEnergy;
    }

    public BuyerListener buyer() {
        return buyer;
    }

    private org.bukkit.scheduler.BukkitScheduler scheduler() {
        return getServer().getScheduler();
    }

    public AdminMenu adminMenu() {
        return adminMenu;
    }

    public WeightService weights() {
        return weights;
    }

    public MapService map() {
        return map;
    }

    public OreRespawnTask oreRespawn() {
        return oreRespawn;
    }

    public ClanService clans() {
        return clans;
    }
}
