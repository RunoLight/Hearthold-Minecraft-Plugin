// Hearthold web portal: map of bases, events and players (by scouting tier), politics and trade routes.
// All data is inserted via textContent — never innerHTML with server data.
(function () {
  "use strict";

  // --- Translations ---
  // The server writes the active plugin language into <html lang="...">; English is the default.
  var I18N = {
    en: {
      loginLead: "The server map and politics are available after you log in.",
      loginBefore: "Type ",
      loginAfter: " in game and open the one-time link from chat.",
      adminPanel: "Admin panel",
      logout: "Log out",
      loading: "Loading…",
      tabMap: "Map",
      tabPolitics: "Politics",
      tabTrade: "Trade",
      hPrime: "Prime time",
      hScouting: "Scouting",
      hLegend: "Legend",
      hEvents: "Events",
      hWars: "Ongoing wars",
      hVassals: "Vassalages",
      hClans: "Clans",
      thClan: "Clan",
      thPlayers: "Players",
      thReputation: "Reputation",
      thPrestige: "Prestige",
      hJournal: "War log",
      hFailures: "Failed trade agreements",
      tradeIntro: "Your clan's trade routes — the schedule book.",

      durHM: "{0} h {1} min",
      durH: "{0} h",
      durM: "{0} min",
      durMS: "{0} min {1} s",
      durS: "{0} s",

      region: "Region \"{0}\": {1}",
      neutralZone: "Neutral zone \"{0}\"",
      fertileZone: "Fertile zone \"{0}\"",
      kindHeart: "Base heart",
      kindNode: "Node",
      kindProxy: "Proxy",
      baseTip: "{0} of clan {1}{2} — {3}, {4}",
      inactive: " (not active yet)",
      eventTip: "Event \"{0}\": {1}",
      courierTip: " — courier with cargo",

      noData: "No data",
      primeOn: "Prime time is on",
      primeOff: "No prime time",
      forcedOn: "Turned on manually by an admin, off schedule.",
      forcedOff: "Turned off manually by an admin, off schedule.",
      schedNow: "On schedule right now: {0}.",
      schedYes: "on",
      schedNo: "off",
      untilEnd: "On schedule, until {0} ({1} left).",
      nextPrime: "Next scheduled: {0} (in {1}).",
      noWindows: "The schedule has no windows.",
      today: "Today",
      daily: "daily",
      serverTime: " · server time ({0})",
      schedule: "Schedule: {0}",

      cellTitle: "{0}, wear {1} per block",
      cellHits: "{0} hits · {1} s",
      cellTools: "{0} tools per block",
      cellBlocks: "{0} blocks per tool",
      noRegen: "does not regenerate",
      scenario: "Scenario",
      noFatigue: "No fatigue",
      fatigueN: "Fatigue {0}",
      block: "Block",
      regenFrom1: "Regen from 1 HP",
      asVanilla: "vanilla",
      levelPill: "lv. {0}",
      membersPill: "{0} players",
      protectedPill: "protected",
      unprotectedPill: "unprotected",
      shieldPill: "shield",
      warPill: "war",
      heartTreasury: "Heart treasury",
      emeraldsN: "{0} emeralds",
      upkeep: "Upkeep",
      perDayN: "{0} per day",
      lastsFor: "Lasts",
      daysN: "{0} days",
      energy: "Energy",
      fatigue: "Fatigue",
      fatigueVal: "{0} (upkeep ×{1})",
      tradeTreasury: "Trade treasury: {0}",
      empty: "empty",
      routeLine: "Route {0} with \"{1}\", every {2} d, {3}: gives {4}; receives {5}",
      noRoutesAdmin: "No trade routes",
      warLine: "War: {0} against \"{1}\", {2}, until {3}",
      warActive: "ongoing",
      warPrep: "preparation",

      eChunk: "Chunk energy",
      eChunkNote: "Plant growth and animal breeding spend chunk energy. A chunk regenerates on its own up to its " +
        "natural maximum; above that, energy is poured in with cells from the base heart, up to the cap.",
      eNaturalMax: "Natural maximum",
      eRegen: "Regeneration",
      eZeroToMax: "Empty to maximum",
      eCap: "Cap (with cells)",
      perHourN: "{0} per hour",
      hoursN: "{0} h",
      eGrowth: "Growth in an ordinary chunk",
      eWhat: "What",
      eCost: "Energy cost",
      eFromFull: "From a full chunk",
      ePerHourRegen: "Per hour of regeneration",
      ePerDay: "Per day",
      eAtCap: "A chunk filled with cells to the cap ({0}): wheat — {1}, breedings — {2}.",
      eFertile: "Fertile zones",
      eFertileNone: "None configured (energy.fertile-zones).",
      eZone: "Zone",
      eMult: "Multiplier",
      eMax: "Maximum",
      eRegenHour: "Regeneration per hour",
      eWheatHour: "Wheat per hour",
      eClan: "Clan energy (base heart)",
      eClanNote: "The heart accumulates {0} per hour + {1} per member + {2} per node + {3} per prestige point, " +
        "up to {4}. A cell holds {5} energy. \"Chunks to fill\" — how many chunks per day can be raised from the " +
        "natural maximum to the cap.",
      eMembers: "Members",
      eNodes: "Nodes",
      ePerHour: "Per hour",
      eToFull: "Until heart is full",
      eCellsDay: "Cells per day",
      eWheatDay: "Wheat per day",
      eChunksFill: "Chunks to fill",

      cTitle: "Collector count penalty",
      cNote: "At most {0} per clan. Each stores up to {1} items and pays out every {2} min. The more collectors " +
        "a clan has, the less each one yields.",
      cCount: "Collectors in clan",
      cEach: "Each yields",
      cPenalty: "Penalty",
      cTotal: "Total, in single-collector yields",

      aNote: "Each cell: hits (break attempts) and time at vanilla breaking speed; how many tools one block " +
        "consumes; how many blocks one tool lasts (without Unbreaking). The tool matches the block: pickaxe, axe " +
        "or shovel of that tier. Updated {0}.",
      wild: "Wilderness",
      wildNote: "{0} \"Regen from 1 HP\" — how long a block takes to recover after being hit almost to breaking.",
      baseTitle: "Base level {0} — {1} HP per protected block",
      baseUpgrade: "Upgrading to this level: {0} emeralds from the heart treasury, upkeep +{1} per day. " +
        "Prime time increases damage only against bases. {2}",
      regenH: "Regeneration",
      regenNote: "Same for all base blocks: {0} HP/s, with a {1} s pause after a hit. A new block is placed " +
        "with 1 HP and starts growing right away. The owner's war fatigue slows regeneration.",
      inPrime: "During prime time",
      offPrime: "Outside prime time (a hit removes 1 HP, wear ×{0})",
      captureH: "Heart capture",
      captureNote: "Prime time only (progress does not grow outside it). Duration {0} s, radius {1} blocks; " +
        "without attackers progress decays by {2} s per second, a defender at the heart stops the capture. " +
        "Result — vassalage for {3} d, shield for the defeated clan {4} h. Fast capture (test): {5}.",
      fastOn: "ON — {0} s, no prime time",
      fastOff: "off",
      activeClans: "Active clans",
      noClans: "No clans",
      baseTab: "Base lv. {0}",
      collectorsTab: "Collectors",

      delayed: " · players delayed {0} s",
      noScouting: " · no scouting",
      mapHint: "Wheel — zoom, drag — pan",
      scout0: "Level 0: only bases and events are visible. The leader raises the level with /site scouting " +
        "(higher upkeep).",
      scoutN: "Level {0}: players outside bases delayed by {1} s",
      scoutCouriers: ", couriers with cargo are marked.",
      eventLine: "\"{0}\" — {1}",
      noEvents: "No events",
      warUntil: "ongoing until {0}",
      prepUntil: "preparation until {0}",
      peace: "Peace",
      vassalLine: "{0}, until {1}",
      none: "None",
      noWarsYet: "No wars yet",
      failLine: "{0} broke the agreement with {1}",
      notYet: "None yet",
      routeHead: "#{0} with {1} — every {2} d, {3}",
      period: "Period",
      cargo: "Cargo",
      direction: "Direction",
      status: "Status",
      noRoutes: "Your clan has no trade routes.",

      lOwn: "Your clan's players",
      lEnemy: "Other players outside bases",
      lCourier: "Courier with cargo (scouting 2+)",
      lEvent: "Event",
      lNeutral: "Neutral zone (canyon, mine)",
      lFertile: "Fertile zone",
      lCircles: "Circles are clan bases (solid — heart and nodes, dashed — proxies); dashed frames are producer regions.",
      noClan: " · no clan"
    },
    ru: {
      loginLead: "Карта и политика сервера доступны после входа.",
      loginBefore: "Напишите в игре ",
      loginAfter: " и откройте одноразовую ссылку из чата.",
      adminPanel: "Админ-панель",
      logout: "Выйти",
      loading: "Загрузка…",
      tabMap: "Карта",
      tabPolitics: "Политика",
      tabTrade: "Торговля",
      hPrime: "Прайм-тайм",
      hScouting: "Разведка",
      hLegend: "Обозначения",
      hEvents: "Ивенты",
      hWars: "Идут войны",
      hVassals: "Вассалитеты",
      hClans: "Кланы",
      thClan: "Клан",
      thPlayers: "Игроков",
      thReputation: "Репутация",
      thPrestige: "Престиж",
      hJournal: "Журнал войн",
      hFailures: "Сорвавшиеся торговые союзы",
      tradeIntro: "Торговые пути вашего клана — книга с графиком.",

      durHM: "{0} ч {1} мин",
      durH: "{0} ч",
      durM: "{0} мин",
      durMS: "{0} мин {1} с",
      durS: "{0} с",

      region: "Регион «{0}»: {1}",
      neutralZone: "Нейтральная зона «{0}»",
      fertileZone: "Плодородная зона «{0}»",
      kindHeart: "Сердце",
      kindNode: "Нода",
      kindProxy: "Прокси",
      baseTip: "{0} клана {1}{2} — {3}, {4}",
      inactive: " (ещё не активна)",
      eventTip: "Ивент «{0}»: {1}",
      courierTip: " — курьер с грузом",

      noData: "Нет данных",
      primeOn: "Прайм-тайм идёт",
      primeOff: "Прайм-тайма нет",
      forcedOn: "Включён администратором вручную, не по графику.",
      forcedOff: "Выключен администратором вручную, не по графику.",
      schedNow: "По графику сейчас: {0}.",
      schedYes: "идёт",
      schedNo: "нет",
      untilEnd: "По графику, до {0} (ещё {1}).",
      nextPrime: "Следующий по графику: {0} (через {1}).",
      noWindows: "В графике нет окон.",
      today: "Сегодня",
      daily: "ежедневно",
      serverTime: " · время сервера ({0})",
      schedule: "График: {0}",

      cellTitle: "{0}, износ {1} за блок",
      cellHits: "{0} уд. · {1} с",
      cellTools: "{0} инстр. на блок",
      cellBlocks: "{0} бл. на инстр.",
      noRegen: "не восстанавливается",
      scenario: "Сценарий",
      noFatigue: "Без усталости",
      fatigueN: "Усталость {0}",
      block: "Блок",
      regenFrom1: "Реген с 1 HP",
      asVanilla: "как в ванилле",
      levelPill: "ур. {0}",
      membersPill: "{0} игр.",
      protectedPill: "защищена",
      unprotectedPill: "без защиты",
      shieldPill: "щит",
      warPill: "война",
      heartTreasury: "Казна сердца",
      emeraldsN: "{0} изумр.",
      upkeep: "Расход",
      perDayN: "{0} в сутки",
      lastsFor: "Хватит на",
      daysN: "{0} сут.",
      energy: "Энергия",
      fatigue: "Усталость",
      fatigueVal: "{0} (upkeep ×{1})",
      tradeTreasury: "Торговая казна: {0}",
      empty: "пусто",
      routeLine: "Маршрут {0} с «{1}», раз в {2} дн., {3}: отдаёт {4}; получает {5}",
      noRoutesAdmin: "Торговых маршрутов нет",
      warLine: "Война: {0} против «{1}», {2}, до {3}",
      warActive: "идёт",
      warPrep: "подготовка",

      eChunk: "Энергия чанка",
      eChunkNote: "Рост растений и размножение животных тратят энергию чанка. Чанк сам восстанавливается до " +
        "естественного максимума; сверх него энергию вливают ячейками из сердца базы, до потолка.",
      eNaturalMax: "Естественный максимум",
      eRegen: "Восстановление",
      eZeroToMax: "С нуля до максимума",
      eCap: "Потолок (ячейками)",
      perHourN: "{0} в час",
      hoursN: "{0} ч",
      eGrowth: "Сколько вырастет в обычном чанке",
      eWhat: "Что",
      eCost: "Цена энергии",
      eFromFull: "Из полного чанка",
      ePerHourRegen: "В час на восстановлении",
      ePerDay: "В сутки",
      eAtCap: "Чанк, заправленный ячейками до потолка ({0}): пшеницы — {1}, размножений — {2}.",
      eFertile: "Плодородные зоны",
      eFertileNone: "Не заданы (energy.fertile-zones).",
      eZone: "Зона",
      eMult: "Множитель",
      eMax: "Максимум",
      eRegenHour: "Восстановление в час",
      eWheatHour: "Пшеницы в час",
      eClan: "Энергия клана (сердце базы)",
      eClanNote: "Сердце копит {0} в час + {1} за участника + {2} за ноду + {3} за очко престижа, до {4}. " +
        "Ячейка — {5} энергии. «Чанков заправить» — сколько чанков в сутки можно поднять от естественного " +
        "максимума до потолка.",
      eMembers: "Участников",
      eNodes: "Нод",
      ePerHour: "В час",
      eToFull: "До полного сердца",
      eCellsDay: "Ячеек в сутки",
      eWheatDay: "Пшеницы в сутки",
      eChunksFill: "Чанков заправить",

      cTitle: "Штраф за количество сборщиков",
      cNote: "Не больше {0} на клан. Каждый копит до {1} предметов, выдача раз в {2} мин. Чем больше сборщиков " +
        "у клана, тем меньше даёт каждый.",
      cCount: "Сборщиков у клана",
      cEach: "Каждый даёт",
      cPenalty: "Штраф",
      cTotal: "Всего, в выходах одного",

      aNote: "В клетке: ударов (попыток слома) и время с ванильной скоростью ломания; сколько инструментов " +
        "уходит на один блок; сколько блоков выдержит один инструмент (без «Прочности»). Инструмент подбирается " +
        "под блок: кирка, топор или лопата этой ступени. Обновлено {0}.",
      wild: "Дикая местность",
      wildNote: "{0} «Реген с 1 HP» — сколько блок восстанавливается после удара почти до слома.",
      baseTitle: "База уровня {0} — {1} HP у защищённого блока",
      baseUpgrade: "Повышение до этого уровня: {0} изумр. из казны сердца, upkeep +{1} в сутки. " +
        "Прайм-тайм увеличивает урон только по базам. {2}",
      regenH: "Регенерация",
      regenNote: "Одинакова для всех блоков базы: {0} HP/с, после удара пауза {1} с. Новый блок ставится с 1 HP " +
        "и сразу начинает расти. Военная усталость владельца замедляет регенерацию.",
      inPrime: "В прайм-тайм",
      offPrime: "Вне прайм-тайма (удар снимает 1 HP, износ ×{0})",
      captureH: "Захват сердца",
      captureNote: "Только в прайм-тайм (вне его прогресс не растёт). Длительность {0} с, радиус {1} блоков; " +
        "без нападающих прогресс спадает на {2} с за секунду, защитник у сердца останавливает захват. " +
        "Итог — вассалитет на {3} дн., щит побеждённому {4} ч. Ускоренный захват (тест): {5}.",
      fastOn: "ВКЛ — {0} с, без прайм-тайма",
      fastOff: "выкл",
      activeClans: "Действующие кланы",
      noClans: "Кланов нет",
      baseTab: "База ур. {0}",
      collectorsTab: "Сборщики",

      delayed: " · игроки с задержкой {0} с",
      noScouting: " · разведки нет",
      mapHint: "Колесо — масштаб, перетаскивание — сдвиг",
      scout0: "Уровень 0: видны только базы и ивенты. Лидер повышает уровень командой /site scouting (дороже upkeep).",
      scoutN: "Уровень {0}: игроки вне баз с задержкой {1} с",
      scoutCouriers: ", курьеры с грузом отмечены.",
      eventLine: "«{0}» — {1}",
      noEvents: "Ивентов нет",
      warUntil: "идёт до {0}",
      prepUntil: "подготовка до {0}",
      peace: "Мир",
      vassalLine: "{0}, до {1}",
      none: "Нет",
      noWarsYet: "Войн ещё не было",
      failLine: "{0} не исполнил договор с {1}",
      notYet: "Пока нет",
      routeHead: "#{0} с {1} — раз в {2} дн., {3}",
      period: "Период",
      cargo: "Груз",
      direction: "Направление",
      status: "Статус",
      noRoutes: "У клана нет торговых путей.",

      lOwn: "Игроки вашего клана",
      lEnemy: "Чужие игроки вне баз",
      lCourier: "Курьер с грузом (разведка 2+)",
      lEvent: "Ивент",
      lNeutral: "Нейтральная зона (каньон, шахта)",
      lFertile: "Плодородная зона",
      lCircles: "Круги — базы кланов (сплошной — сердце и ноды, пунктир — прокси), пунктирные рамки — регионы-производители.",
      noClan: " · без клана"
    }
  };

  var lang = I18N[document.documentElement.lang] ? document.documentElement.lang : "en";
  var locale = lang === "ru" ? "ru-RU" : "en-GB";

  /** Translated string with {0}, {1}, … replaced by the remaining arguments. */
  function t(key) {
    var s = I18N[lang][key];
    if (s === undefined) s = I18N.en[key];
    if (s === undefined) return key;
    var args = arguments;
    return s.replace(/\{(\d+)\}/g, function (m, i) {
      var v = args[Number(i) + 1];
      return v === undefined ? m : String(v);
    });
  }

  function applyStatic() {
    document.documentElement.lang = lang;
    document.querySelectorAll("[data-i18n]").forEach(function (e) { e.textContent = t(e.dataset.i18n); });
  }

  applyStatic();

  var canvas = document.getElementById("map");
  var ctx = canvas.getContext("2d");
  var tip = document.getElementById("tip");
  var statusEl = document.getElementById("status");
  var view = { cx: 0, cz: 0, scale: 0.2, fitted: false };
  var mapData = null;
  var hitboxes = [];

  function css(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function el(tag, text, cls) {
    var e = document.createElement(tag);
    if (text !== undefined && text !== null) e.textContent = String(text);
    if (cls) e.className = cls;
    return e;
  }

  function fmtTime(ms) {
    var d = new Date(ms);
    return d.toLocaleDateString(locale, { day: "numeric", month: "2-digit" }) + " " +
      d.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });
  }

  function left(ms) {
    var min = Math.max(0, Math.ceil((ms - Date.now()) / 60000));
    return min >= 60 ? t("durHM", Math.floor(min / 60), min % 60) : t("durM", min);
  }

  // Stable clan colour derived from its name.
  function clanColor(name) {
    var h = 0;
    for (var i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
    return "hsl(" + (h % 360) + ", 55%, 48%)";
  }

  function getJson(url) {
    return fetch(url, { credentials: "same-origin" }).then(function (r) {
      if (r.status === 401) {
        document.getElementById("app").hidden = true;
        document.getElementById("login").style.display = "block";
        throw new Error("login");
      }
      return r.json();
    });
  }

  // --- Tabs ---
  document.querySelectorAll("nav button").forEach(function (b) {
    b.addEventListener("click", function () {
      document.querySelectorAll("nav button").forEach(function (x) { x.classList.toggle("on", x === b); });
      document.querySelectorAll("aside section").forEach(function (s) {
        s.classList.toggle("on", s.id === b.dataset.tab);
      });
    });
  });

  // --- Map ---
  function resize() {
    var r = canvas.getBoundingClientRect();
    var dpr = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.round(r.width * dpr));
    canvas.height = Math.max(1, Math.round(r.height * dpr));
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    draw();
  }

  function toScreen(x, z) {
    var r = canvas.getBoundingClientRect();
    return [r.width / 2 + (x - view.cx) * view.scale, r.height / 2 + (z - view.cz) * view.scale];
  }

  function toWorld(sx, sy) {
    var r = canvas.getBoundingClientRect();
    return [view.cx + (sx - r.width / 2) / view.scale, view.cz + (sy - r.height / 2) / view.scale];
  }

  function fit() {
    var w = mapData && mapData.world;
    if (!w || w.minX === undefined) return;
    var r = canvas.getBoundingClientRect();
    view.cx = (w.minX + w.maxX) / 2;
    view.cz = (w.minZ + w.maxZ) / 2;
    view.scale = Math.min(r.width / (w.maxX - w.minX), r.height / (w.maxZ - w.minZ)) * 0.95;
    view.fitted = true;
  }

  function draw() {
    var r = canvas.getBoundingClientRect();
    ctx.clearRect(0, 0, r.width, r.height);
    hitboxes = [];
    if (!mapData) return;
    var w = mapData.world;

    // World border.
    if (w.minX !== undefined) {
      var a = toScreen(w.minX, w.minZ), b = toScreen(w.maxX, w.maxZ);
      ctx.strokeStyle = css("--grid");
      ctx.lineWidth = 2;
      ctx.strokeRect(a[0], a[1], b[0] - a[0], b[1] - a[1]);
    }

    // Producer regions and zones.
    mapData.regions.concat(mapData.zones).forEach(function (z) {
      var p = toScreen(z.minX, z.minZ), q = toScreen(z.maxX + 1, z.maxZ + 1);
      if (z.type === "region") {
        ctx.setLineDash([6, 4]);
        ctx.strokeStyle = css("--region");
        ctx.lineWidth = 1;
        ctx.strokeRect(p[0], p[1], q[0] - p[0], q[1] - p[1]);
        ctx.setLineDash([]);
        ctx.fillStyle = css("--region");
        ctx.font = "12px system-ui";
        ctx.fillText(z.name, p[0] + 4, p[1] + 14);
      } else {
        ctx.fillStyle = css(z.type === "neutral" ? "--neutral" : "--fertile");
        ctx.fillRect(p[0], p[1], q[0] - p[0], q[1] - p[1]);
      }
      hitboxes.push({ x: (p[0] + q[0]) / 2, y: (p[1] + q[1]) / 2, r: Math.max(8, Math.min(q[0] - p[0], q[1] - p[1]) / 2),
        text: (z.type === "region" ? t("region", z.name, (z.resources || []).join(", "))
          : z.type === "neutral" ? t("neutralZone", z.name) : t("fertileZone", z.name)) });
    });

    // Bases: hearts, nodes, proxies.
    mapData.bases.forEach(function (s) {
      var c = toScreen(s.x, s.z);
      var rad = Math.max(3, s.radius * view.scale);
      var color = clanColor(s.clan);
      ctx.beginPath();
      ctx.arc(c[0], c[1], rad, 0, Math.PI * 2);
      ctx.globalAlpha = s.active ? 0.22 : 0.08;
      ctx.fillStyle = color;
      ctx.fill();
      ctx.globalAlpha = 1;
      ctx.lineWidth = s.kind === "HEART" ? 2 : 1;
      ctx.setLineDash(s.kind === "PROXY" ? [4, 3] : []);
      ctx.strokeStyle = color;
      ctx.stroke();
      ctx.setLineDash([]);
      if (s.kind === "HEART") {
        ctx.fillStyle = css("--ink");
        ctx.font = "bold 12px system-ui";
        ctx.textAlign = "center";
        ctx.fillText(s.clan, c[0], c[1] - rad - 4);
        ctx.textAlign = "left";
      }
      var kind = s.kind === "HEART" ? t("kindHeart") : s.kind === "NODE" ? t("kindNode") : t("kindProxy");
      hitboxes.push({ x: c[0], y: c[1], r: Math.max(6, rad),
        text: t("baseTip", kind, s.clan, s.active ? "" : t("inactive"), s.x, s.z) });
    });

    // Events.
    mapData.events.forEach(function (e) {
      if (e.world !== w.name) return;
      var c = toScreen(e.x, e.z);
      ctx.fillStyle = css("--accent");
      ctx.beginPath();
      for (var i = 0; i < 10; i++) {
        var ang = Math.PI / 5 * i - Math.PI / 2, rr = i % 2 ? 4 : 9;
        ctx.lineTo(c[0] + Math.cos(ang) * rr, c[1] + Math.sin(ang) * rr);
      }
      ctx.closePath();
      ctx.fill();
      hitboxes.push({ x: c[0], y: c[1], r: 10, text: t("eventTip", e.name, e.state) });
    });

    // Players.
    mapData.players.forEach(function (p) {
      var c = toScreen(p.x, p.z);
      ctx.fillStyle = css(p.own ? "--own" : p.courier ? "--courier" : "--enemy");
      ctx.beginPath();
      if (p.courier) {
        ctx.moveTo(c[0], c[1] - 6); ctx.lineTo(c[0] + 6, c[1]); ctx.lineTo(c[0], c[1] + 6); ctx.lineTo(c[0] - 6, c[1]);
      } else {
        ctx.arc(c[0], c[1], 4.5, 0, Math.PI * 2);
      }
      ctx.fill();
      hitboxes.push({ x: c[0], y: c[1], r: 7, text: p.name + (p.clan ? " (" + p.clan + ")" : "") +
        (p.courier ? t("courierTip") : "") });
    });
  }

  // Drag and zoom.
  var drag = null;
  canvas.addEventListener("pointerdown", function (e) {
    drag = { x: e.clientX, y: e.clientY, cx: view.cx, cz: view.cz };
    canvas.setPointerCapture(e.pointerId);
    canvas.style.cursor = "grabbing";
  });
  canvas.addEventListener("pointerup", function () { drag = null; canvas.style.cursor = "grab"; });
  canvas.addEventListener("pointermove", function (e) {
    var r = canvas.getBoundingClientRect();
    var sx = e.clientX - r.left, sy = e.clientY - r.top;
    if (drag) {
      view.cx = drag.cx - (e.clientX - drag.x) / view.scale;
      view.cz = drag.cz - (e.clientY - drag.y) / view.scale;
      draw();
      return;
    }
    var hit = null;
    for (var i = hitboxes.length - 1; i >= 0; i--) {
      var h = hitboxes[i];
      if ((h.x - sx) * (h.x - sx) + (h.y - sy) * (h.y - sy) <= h.r * h.r) { hit = h; break; }
    }
    var wpos = toWorld(sx, sy);
    if (hit) {
      tip.textContent = hit.text;
      tip.style.display = "block";
      tip.style.left = Math.min(sx + 14, r.width - 270) + "px";
      tip.style.top = (sy + 14) + "px";
    } else {
      tip.style.display = "none";
    }
    statusEl.textContent = "x " + Math.round(wpos[0]) + ", z " + Math.round(wpos[1]) + statusSuffix;
  });
  canvas.addEventListener("pointerleave", function () { tip.style.display = "none"; });
  canvas.addEventListener("wheel", function (e) {
    e.preventDefault();
    var r = canvas.getBoundingClientRect();
    var sx = e.clientX - r.left, sy = e.clientY - r.top;
    var before = toWorld(sx, sy);
    view.scale = Math.min(8, Math.max(0.01, view.scale * (e.deltaY < 0 ? 1.2 : 1 / 1.2)));
    var after = toWorld(sx, sy);
    view.cx += before[0] - after[0];
    view.cz += before[1] - after[1];
    draw();
  }, { passive: false });
  window.addEventListener("resize", resize);

  var statusSuffix = "";

  // --- Prime time ---
  function zoned(ms, zone, opts) {
    opts.timeZone = zone;
    try { return new Date(ms).toLocaleString(locale, opts); }
    catch (e) { delete opts.timeZone; return new Date(ms).toLocaleString(locale, opts); }
  }

  function renderPrime(p) {
    var box = document.getElementById("prime");
    box.replaceChildren();
    box.className = "";
    if (!p) { box.appendChild(el("div", t("noData"), "muted")); return; }
    var hm = { hour: "2-digit", minute: "2-digit" };
    var status = el("div", null, "prime-status");
    var dot = el("span", null, "dot" + (p.active ? " on" : ""));
    status.appendChild(dot);
    status.appendChild(el("span", p.active ? t("primeOn") : t("primeOff")));
    box.appendChild(status);

    var notes = [];
    if (p.forced !== null && p.forced !== undefined) {
      notes.push([p.forced ? t("forcedOn") : t("forcedOff"), "warn"]);
      notes.push([t("schedNow", p.scheduled ? t("schedYes") : t("schedNo")), ""]);
    } else if (p.active && p.currentEnd) {
      notes.push([t("untilEnd", zoned(p.currentEnd, p.zone, hm), left(p.currentEnd)), ""]);
    }
    if (!p.active || p.forced !== null) {
      if (p.nextStart) {
        notes.push([t("nextPrime", zoned(p.nextStart, p.zone,
          { weekday: "short", hour: "2-digit", minute: "2-digit" }), left(p.nextStart)), ""]);
      } else if (!p.windows.length) {
        notes.push([t("noWindows"), ""]);
      }
    }
    notes.forEach(function (n) { box.appendChild(el("div", n[0], "prime-note " + n[1])); });

    // Week: one row per day, prime-time windows in red, a black line marks now.
    var week = el("div", null, "week");
    var dayMs = 86400000;
    p.days.forEach(function (start, i) {
      var end = i + 1 < p.days.length ? p.days[i + 1] : start + dayMs;
      var span = end - start; // accounts for daylight saving changes
      var row = el("div", null, "week-row" + (i === 0 ? " today" : ""));
      row.appendChild(el("span", i === 0 ? t("today") : zoned(start + 3600000, p.zone, { weekday: "short", day: "numeric" }), "day"));
      var track = el("div", null, "track");
      p.windows.forEach(function (w) {
        var a = Math.max(w[0], start), b = Math.min(w[1], end);
        if (b <= a) return;
        var bar = el("div", null, "win");
        bar.style.left = ((a - start) / span * 100) + "%";
        bar.style.width = ((b - a) / span * 100) + "%";
        bar.title = zoned(w[0], p.zone, hm) + "–" + zoned(w[1], p.zone, hm);
        track.appendChild(bar);
      });
      var now = Date.now();
      if (now >= start && now < end) {
        var mark = el("div", null, "now");
        mark.style.left = ((now - start) / span * 100) + "%";
        track.appendChild(mark);
      }
      row.appendChild(track);
      week.appendChild(row);
    });
    var hours = el("div", null, "hours");
    hours.appendChild(el("div", ""));
    var ticks = el("div");
    ["0", "6", "12", "18", "24"].forEach(function (h) { ticks.appendChild(el("span", h)); });
    hours.appendChild(ticks);
    week.appendChild(hours);
    box.appendChild(week);
    var sched = (p.dayLabels.length === 7 ? t("daily") : p.dayLabels.join(", ")) + ", " +
      (p.windowLabels.join(", ") || "—") + t("serverTime", p.zone);
    box.appendChild(el("div", t("schedule", sched), "prime-note"));
  }

  // --- Admin panel (operators only) ---
  var adminData = null;
  var adminTab = "wild";

  function econCell(c) {
    var td = el("td");
    if (!c) { td.textContent = "—"; return td; }
    td.title = t("cellTitle", c.tool, c.wear);
    td.appendChild(el("div", t("cellHits", c.hits, c.seconds), "c1"));
    td.appendChild(el("div", t("cellTools", c.toolsPerBlock), "c2"));
    td.appendChild(el("div", t("cellBlocks", c.blocksPerTool), "c3"));
    return td;
  }

  function fmtDur(sec) {
    if (sec === null || sec === undefined) return "—";
    if (sec < 0) return t("noRegen");
    if (sec < 60) return t("durS", sec);
    var m = Math.floor(sec / 60), r = sec % 60;
    if (m < 60) return r ? t("durMS", m, r) : t("durM", m);
    var h = Math.floor(m / 60);
    return m % 60 ? t("durHM", h, m % 60) : t("durH", h);
  }

  function regenTable(r) {
    var wrap = el("div", null, "econ");
    var tbl = el("table");
    tbl.style.minWidth = "0";
    var head = el("tr");
    head.appendChild(el("th", t("scenario")));
    r.fatigue.forEach(function (f) { head.appendChild(el("th", f === 0 ? t("noFatigue") : t("fatigueN", f))); });
    var thead = el("thead");
    thead.appendChild(head);
    tbl.appendChild(thead);
    var tb = el("tbody");
    r.rows.forEach(function (row) {
      var tr = el("tr");
      tr.appendChild(el("td", row.label));
      row.seconds.forEach(function (sec) { tr.appendChild(el("td", fmtDur(sec), "c1")); });
      tb.appendChild(tr);
    });
    tbl.appendChild(tb);
    wrap.appendChild(tbl);
    return wrap;
  }

  function econTable(rows, tiers) {
    var wrap = el("div", null, "econ");
    var tbl = el("table");
    var head = el("tr");
    head.appendChild(el("th", t("block")));
    head.appendChild(el("th", "HP"));
    var withRegen = rows.length && rows[0].hasOwnProperty("regenSeconds");
    if (withRegen) head.appendChild(el("th", t("regenFrom1")));
    tiers.forEach(function (n) { head.appendChild(el("th", n)); });
    var thead = el("thead");
    thead.appendChild(head);
    tbl.appendChild(thead);
    var tb = el("tbody");
    rows.forEach(function (r) {
      var tr = el("tr");
      tr.appendChild(el("td", r.block, "blk"));
      tr.appendChild(el("td", r.vanilla ? t("asVanilla") : r.hp));
      if (withRegen) tr.appendChild(el("td", fmtDur(r.regenSeconds)));
      r.cells.forEach(function (c) { tr.appendChild(econCell(c)); });
      tb.appendChild(tr);
    });
    tbl.appendChild(tb);
    wrap.appendChild(tbl);
    return wrap;
  }

  function clanCard(c) {
    var card = el("div", null, "clan-card");
    var h = el("h3", c.name);
    h.appendChild(el("span", t("levelPill", c.level), "pill"));
    h.appendChild(el("span", t("membersPill", c.members), "pill"));
    h.appendChild(el("span", c.protected ? t("protectedPill") : t("unprotectedPill"), "pill " + (c.protected ? "good" : "bad")));
    if (c.shielded) h.appendChild(el("span", t("shieldPill"), "pill good"));
    if (c.status) h.appendChild(el("span", c.status, "pill bad"));
    if (c.wars.length) h.appendChild(el("span", t("warPill"), "pill bad"));
    card.appendChild(h);
    var kv = el("div", null, "kv");
    [[t("heartTreasury"), t("emeraldsN", c.emeralds)],
     [t("upkeep"), t("perDayN", c.dailyCost)],
     [t("lastsFor"), c.daysLeft === null ? "—" : t("daysN", c.daysLeft)],
     [t("energy"), c.energy],
     [t("fatigue"), t("fatigueVal", c.fatigue, c.upkeepMultiplier)],
     [t("thReputation"), c.reputation]].forEach(function (p) {
      var row = el("div");
      row.appendChild(el("span", p[0] + ": ", "muted"));
      row.appendChild(el("span", p[1]));
      kv.appendChild(row);
    });
    card.appendChild(kv);
    var goods = Object.keys(c.tradeTreasury).map(function (k) { return c.tradeTreasury[k] + " " + k; });
    card.appendChild(el("div", t("tradeTreasury", goods.length ? goods.join(", ") : t("empty")), "muted"));
    if (c.routes.length) {
      var ul = el("ul");
      c.routes.forEach(function (r) {
        ul.appendChild(el("li", t("routeLine", r.id, r.partner, r.periodDays, r.status,
          r.gives.join(", ") || "—", r.takes.join(", ") || "—")));
      });
      card.appendChild(ul);
    } else {
      card.appendChild(el("div", t("noRoutesAdmin"), "muted"));
    }
    c.wars.forEach(function (w) {
      card.appendChild(el("div", t("warLine", w.role, w.against, w.active ? t("warActive") : t("warPrep"),
        fmtTime(w.endsAt))));
    });
    return card;
  }

  function simpleTable(head, rows) {
    var wrap = el("div", null, "econ");
    var tbl = el("table");
    tbl.style.minWidth = "0";
    var tr = el("tr");
    head.forEach(function (h) { tr.appendChild(el("th", h)); });
    var thead = el("thead");
    thead.appendChild(tr);
    tbl.appendChild(thead);
    var tb = el("tbody");
    rows.forEach(function (r) {
      var row = el("tr");
      r.forEach(function (v) { row.appendChild(el("td", v === null || v === undefined ? "—" : v)); });
      tb.appendChild(row);
    });
    tbl.appendChild(tb);
    wrap.appendChild(tbl);
    return wrap;
  }

  function renderEnergy(body, e) {
    body.appendChild(el("h2", t("eChunk")));
    body.appendChild(el("p", t("eChunkNote"), "muted"));
    body.appendChild(simpleTable([t("eNaturalMax"), t("eRegen"), t("eZeroToMax"), t("eCap")],
      [[e.chunk.naturalMax, t("perHourN", e.chunk.regenPerHour),
        e.chunk.hoursToFill === null ? "—" : t("hoursN", e.chunk.hoursToFill), e.chunk.cap]]));
    body.appendChild(el("h2", t("eGrowth")));
    body.appendChild(simpleTable([t("eWhat"), t("eCost"), t("eFromFull"), t("ePerHourRegen"), t("ePerDay")],
      e.growth.map(function (g) { return [g.what, g.cost, g.fromFull, g.perHour, g.perDay]; })));
    body.appendChild(el("p", t("eAtCap", e.chunk.cap, e.growthAtCap[0].fromFull, e.growthAtCap[1].fromFull), "muted"));
    body.appendChild(el("h2", t("eFertile")));
    if (!e.fertile.length) {
      body.appendChild(el("p", t("eFertileNone"), "muted"));
    } else {
      body.appendChild(simpleTable([t("eZone"), t("eMult"), t("eMax"), t("eRegenHour"), t("eWheatHour")],
        e.fertile.map(function (f) { return [f.name, "×" + f.multiplier, f.naturalMax, f.regenPerHour, f.wheatPerHour]; })));
    }
    body.appendChild(el("h2", t("eClan")));
    body.appendChild(el("p", t("eClanNote", e.heartBase, e.perMember, e.perNode, e.perPrestige, e.heartMax, e.cellSize),
      "muted"));
    body.appendChild(simpleTable([t("eMembers"), t("eNodes"), t("ePerHour"), t("ePerDay"), t("eToFull"), t("eCellsDay"),
      t("eWheatDay"), t("eChunksFill")],
      e.clanExamples.map(function (r) {
        return [r.members, r.nodes, r.perHour, r.perDay, r.hoursToMax === null ? "—" : t("hoursN", r.hoursToMax),
          r.cellsPerDay, r.wheatPerDay, r.chunksFilled];
      })));
  }

  function renderCollectors(body, c) {
    body.appendChild(el("h2", t("cTitle")));
    body.appendChild(el("p", t("cNote", c.maxPerClan, c.maxItems, c.intervalMinutes), "muted"));
    body.appendChild(simpleTable([t("cCount"), t("cEach"), t("cPenalty"), t("cTotal")],
      c.penalty.map(function (r) { return [r.count, r.eachPercent + "%", "−" + r.penaltyPercent + "%", "×" + r.totalVsOne]; })));
  }

  function renderAdmin() {
    var body = document.getElementById("adminBody");
    body.replaceChildren();
    body.className = "";
    if (!adminData) { body.appendChild(el("p", t("loading"), "muted")); return; }
    var d = adminData;
    var note = t("aNote", fmtTime(d.at));
    if (adminTab === "wild") {
      body.appendChild(el("h2", t("wild")));
      body.appendChild(el("p", t("wildNote", note), "muted"));
      body.appendChild(econTable(d.wild, d.tiers));
    } else if (adminTab.indexOf("base") === 0) {
      var lv = d.base[Number(adminTab.slice(4))];
      if (!lv) { adminTab = "wild"; renderAdmin(); return; }
      body.appendChild(el("h2", t("baseTitle", lv.name, lv.hp)));
      body.appendChild(el("p", t("baseUpgrade", lv.upgradeCost, lv.upkeepPerDay, note), "muted"));
      body.appendChild(el("h2", t("regenH")));
      body.appendChild(el("p", t("regenNote", lv.regen.perSecond, lv.regen.delaySeconds), "muted"));
      body.appendChild(regenTable(lv.regen));
      body.appendChild(el("h2", t("inPrime")));
      body.appendChild(econTable(lv.prime, d.tiers));
      var cap = d.capture;
      body.appendChild(el("h2", t("offPrime", cap.offPrimeWear)));
      body.appendChild(econTable(lv.offPrime, d.tiers));
      body.appendChild(el("h2", t("captureH")));
      body.appendChild(el("p", t("captureNote", cap.seconds, cap.radius, cap.decay, cap.vassalDays, cap.shieldHours,
        cap.fast ? t("fastOn", cap.fastSeconds) : t("fastOff")), "muted"));
    } else if (adminTab === "energy") {
      renderEnergy(body, d.energy);
    } else if (adminTab === "collectors") {
      renderCollectors(body, d.collectors);
    } else {
      body.appendChild(el("h2", t("activeClans")));
      if (!d.clans.length) body.appendChild(el("p", t("noClans"), "muted"));
      d.clans.forEach(function (c) { body.appendChild(clanCard(c)); });
    }
  }

  function adminTabs() {
    var bar = document.getElementById("adminTabs");
    bar.replaceChildren();
    var tabs = [["wild", t("wild")]];
    (adminData ? adminData.base : []).forEach(function (lv, i) { tabs.push(["base" + i, t("baseTab", lv.name)]); });
    tabs.push(["energy", t("energy")]);
    tabs.push(["collectors", t("collectorsTab")]);
    tabs.push(["clans", t("hClans")]);
    tabs.forEach(function (tab) {
      var b = el("button", tab[1], tab[0] === adminTab ? "on" : "");
      b.addEventListener("click", function () { adminTab = tab[0]; adminTabs(); renderAdmin(); });
      bar.appendChild(b);
    });
  }

  function loadAdmin() {
    getJson("/api/admin").then(function (d) { adminData = d; adminTabs(); renderAdmin(); }).catch(function () {});
  }

  function setupAdmin() {
    var btn = document.getElementById("adminBtn");
    var panel = document.getElementById("adminPanel");
    btn.hidden = false;
    btn.addEventListener("click", function () {
      var open = !panel.classList.contains("on");
      panel.classList.toggle("on", open);
      btn.classList.toggle("on", open);
      if (open) loadAdmin();
    });
    setInterval(function () { if (panel.classList.contains("on")) loadAdmin(); }, 30000);
  }

  // --- Data ---
  function loadMap() {
    getJson("/api/map").then(function (d) {
      mapData = d;
      renderPrime(d.world && d.world.prime);
      if (!view.fitted) fit();
      statusSuffix = d.delaySeconds >= 0 ? t("delayed", d.delaySeconds) : t("noScouting");
      if (!statusEl.textContent) statusEl.textContent = t("mapHint") + statusSuffix;
      var info = document.getElementById("scoutInfo");
      info.textContent = d.scouting === 0
        ? t("scout0")
        : t("scoutN", d.scouting, d.delaySeconds) + (d.scouting >= 2 ? t("scoutCouriers") : ".");
      var ev = document.getElementById("eventList");
      ev.replaceChildren();
      d.events.forEach(function (e) {
        var li = el("li", t("eventLine", e.name, e.state));
        if (e.nextAt > Date.now()) li.appendChild(el("span", " · " + left(e.nextAt), "muted"));
        li.appendChild(el("div", e.world + " " + e.x + ", " + e.z, "muted"));
        ev.appendChild(li);
      });
      if (!d.events.length) ev.appendChild(el("li", t("noEvents"), "muted"));
      draw();
    }).catch(function () {});
  }

  function loadPolitics() {
    getJson("/api/politics").then(function (d) {
      var wars = document.getElementById("wars");
      wars.replaceChildren();
      d.wars.forEach(function (w) {
        var li = el("li", w.attacker + " → " + w.defender);
        li.appendChild(el("div", w.active ? t("warUntil", fmtTime(w.endsAt)) : t("prepUntil", fmtTime(w.activeAt)), "muted"));
        wars.appendChild(li);
      });
      if (!d.wars.length) wars.appendChild(el("li", t("peace"), "muted"));

      var vassals = document.getElementById("vassals");
      vassals.replaceChildren();
      d.vassalages.forEach(function (v) {
        var li = el("li", v.vassal + " → " + v.suzerain);
        li.appendChild(el("div", t("vassalLine", v.kind, fmtTime(v.endsAt)), "muted"));
        vassals.appendChild(li);
      });
      if (!d.vassalages.length) vassals.appendChild(el("li", t("none"), "muted"));

      var clans = document.getElementById("clans");
      clans.replaceChildren();
      d.clans.sort(function (a, b) { return b.reputation - a.reputation || b.prestige - a.prestige; });
      d.clans.forEach(function (c) {
        var tr = el("tr");
        var name = el("td", c.name + (c.shield ? " 🛡" : ""));
        if (c.status) name.appendChild(el("div", c.status, "muted"));
        tr.appendChild(name);
        tr.appendChild(el("td", c.members));
        tr.appendChild(el("td", c.reputation));
        tr.appendChild(el("td", c.prestige));
        clans.appendChild(tr);
      });

      var journal = document.getElementById("journal");
      journal.replaceChildren();
      d.journal.forEach(function (w) {
        var li = el("li", w.attacker + " → " + w.defender);
        li.appendChild(el("div", fmtTime(w.declaredAt) + " · " + (w.outcome || t("warActive")), "muted"));
        journal.appendChild(li);
      });
      if (!d.journal.length) journal.appendChild(el("li", t("noWarsYet"), "muted"));

      var failures = document.getElementById("failures");
      failures.replaceChildren();
      d.failures.forEach(function (f) {
        var li = el("li", t("failLine", f.offender, f.victim));
        li.appendChild(el("div", fmtTime(f.at) + " · " + f.reason, "muted"));
        failures.appendChild(li);
      });
      if (!d.failures.length) failures.appendChild(el("li", t("notYet"), "muted"));

      var routes = document.getElementById("routes");
      routes.replaceChildren();
      d.routes.forEach(function (r) {
        routes.appendChild(el("h2", t("routeHead", r.id, r.partner, r.periodDays, r.status)));
        var table = el("table");
        var head = el("tr");
        [t("period"), t("cargo"), t("direction"), t("status")].forEach(function (h) { head.appendChild(el("th", h)); });
        table.appendChild(head);
        r.rows.forEach(function (row) {
          var tr = el("tr");
          var from = new Date(row.from), to = new Date(row.to);
          tr.appendChild(el("td", from.getDate() + "." + (from.getMonth() + 1) + "–" + to.getDate() + "." + (to.getMonth() + 1)));
          tr.appendChild(el("td", row.cargo));
          tr.appendChild(el("td", row.direction));
          tr.appendChild(el("td", row.status));
          table.appendChild(tr);
        });
        routes.appendChild(table);
      });
      if (!d.routes.length) routes.appendChild(el("p", t("noRoutes"), "muted"));
    }).catch(function () {});
  }

  function legend() {
    var items = [
      ["--own", t("lOwn")],
      ["--enemy", t("lEnemy")],
      ["--courier", t("lCourier")],
      ["--accent", t("lEvent")],
      ["--neutral", t("lNeutral")],
      ["--fertile", t("lFertile")]
    ];
    var ul = document.getElementById("legendList");
    items.forEach(function (it) {
      var li = el("li");
      var sw = el("span", null, "sw");
      sw.style.background = "var(" + it[0] + ")";
      li.appendChild(sw);
      li.appendChild(el("span", it[1]));
      ul.appendChild(li);
    });
    ul.appendChild(el("li", t("lCircles"), "muted"));
  }

  getJson("/api/me").then(function (me) {
    // /api/me also reports the language, in case the page was served without it.
    if (me.lang && I18N[me.lang] && me.lang !== lang) {
      lang = me.lang;
      locale = lang === "ru" ? "ru-RU" : "en-GB";
      applyStatic();
    }
    document.getElementById("app").hidden = false;
    document.getElementById("me").textContent = me.player + (me.clan ? " · " + me.clan : t("noClan"));
    legend();
    if (me.admin) setupAdmin();
    resize();
    loadMap();
    loadPolitics();
    setInterval(loadMap, 5000);
    setInterval(loadPolitics, 30000);
  }).catch(function () {});
})();
