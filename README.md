# QweTnts

Плагин-аддон для [QweProtectStones](https://github.com/qweyns/QweProtectionStones) — кастомные
динамиты в стиле **HolyWorld Lite** (осадный прогрыз обсидиана, рейд-блоки, деградация
древних обломков). Полное ТЗ — [`docs/DYNAMITE_ADDON_API.md`](https://github.com/qweyns/QweProtectionStones/blob/main/docs/DYNAMITE_ADDON_API.md) в репозитории QPS.

## Что реализовано (production-ready)

- 🧨 **Четыре стандартных динамита**, настраиваемых отдельными YAML-файлами в `dynamites/`:
  - **Динамит A** — ×3 радиус урона осаде, фитиль 5 сек, крафт из TNT;
  - **Динамит B** — ×10 радиус урона осаде, крафт **из динамита A** через PDC-матчер
    (`PrepareItemCraftEvent`), чтобы не было обхода ванилью;
  - **C4** — ломает обсидиан/плачущий обсидиан, вредит незерит-приватам, рейд-блок 5 мин;
  - **Разрывная волна** — ломает обсидиан, превращает древние обломки (`ANCIENT_DEBRIS`) в обычный обсидиан,
    работает в воде/лаве, сносит **2 прочности** привата, рейд-блок 5 мин.
- 🔌 **Интеграция с QPS** через публичные события (§3.1, §3.2):
  - `RegionExplosionTypeEvent` — классификация взрыва + множитель радиуса;
  - `RegionDamageEvent` — урон прочности 2 для Разрывной волны;
  - обязательная проверка флага `EXPLOSION_DAMAGE` и `region.isCore(...)` перед добавлением
    блоков в `blockList()` (§5.2).
- 🧊 **Рейд-блоки**:
  - 5 минут на месте OBSIDIAN/CRYING_OBSIDIAN/ANCIENT_DEBRIS;
  - запрет постановки в этих позициях на `LOWEST` приоритете раньше QPS (анти-феникс);
  - хранение в памяти (`ConcurrentHashMap`), **атомарная запись** в `raid-blocks.yml`
    (tmp-файл + `Files.move` с `ATOMIC_MOVE`);
  - периодическая чистка истёкших + автосохранение раз в 2 минуты на выделенном I/O-пуле;
  - сериализация/десериализация переживает рестарт сервера.
- ♻️ **Деградация блоков** (Разрывная волна): древние обломки превращаются в обсидиан;
  ядра приватов (`isCore`) не трогаем ни при каких обстоятельствах.
- 🛡️ **Анти-лаг** (§7):
  - лимит одновременно горящих TNTPrimed на игрока и на чанк;
  - кулдаун активации на игрока (`activation-cooldown-millis`);
  - предупреждение в консоль при `power > 16` (риск фриза);
  - зарезервирована настройка тиковой очереди для сверх-больших взрывов (Фаза 2).
- 🌍 **Ограничения по мирам/зонам**:
  - секция `settings.worlds` с режимами `ALLOWED`/`BLOCKED`;
  - радиус защиты спавна обычного мира (`spawn-radius`).
- 🔐 **Права по типам**: `qwetnts.type.<dynamite_id>` с фоллбеком на `qwetnts.use`.
- 🔤 **i18n/l10n**: все сообщения — в `lang/ru_RU.yml` и `lang/en_US.yml`,
  архитектура и оформление один-в-один как в QweProtectStones
  (`LanguageManager` + `%prefix%` + MiniMessage + legacy `&c/&#RRGGBB`).
  Отсутствующий ключ не уходит в чат сырым текстом.
- 🔥 **Два режима поджога**: динамит либо ставится блоком и ждёт огня
  (по умолчанию, `auto-ignite: false`), либо загорается сразу из руки.
  Установленные заряды можно поджечь огнивом, огнём, лавой, ударом или
  другим взрывом — цепная детонация настраивается.
- 🧱 **Модель взрывоустойчивости**: решение «ломать блок или нет» считается по
  ванильной формуле `(resistance + 0.3) * scale` с потолком `max-resistance`
  и явными правилами по материалам (см. ниже).
- 📊 **bStats** (plugin id **34030**):
  - количество загруженных динамита;
  * счётчик взрывов по типам (AdvancedPie);
  - число активных рейд-блоков;
  - drilldown по конфигурации типов.
- 📋 **Логирование уничтоженных приватов** (`RegionDeleteEvent.DESTROYED_BY_RAID`) в
  консоль в формате `[RAID] ...` + счётчики в статистике.
- 🍳 **Folia-совместимость**:
  - все блоковые задачи используют `RegionScheduler` (через обёртку `Schedulers.runAtLocation`);
  - I/O и периодическая чистка — `AsyncScheduler`;
  - именованный `ExecutorService` для фоновой записи файлов;
  - корректная отмена всех задач и шатдаун пула потоков в `onDisable()`.
- 🔧 **Команды** `/qtnt reload|list|give` с табуляцией;
- 📦 **maven-shade-plugin** с релокацией `org.bstats` в `ru.qweyns.qwetnts.libs.bstats`;
- 🧪 Базовые JUnit-тесты + GitHub Actions CI на JDK 21 (собирает jar-артефакт).

## Механика поджога

По умолчанию (`settings.dynamites.auto-ignite: false`) динамит **не поджигает
сам себя**: игрок ставит его обычным блоком TNT, а поджигает отдельно — как на
HolyWorld.

| Как поджечь | Условие |
|---|---|
| Огниво / огненный заряд | всегда |
| Огонь, лава, молния, распространение огня | всегда |
| Другой взрыв (цепная детонация) | `settings.dynamites.chain-radius` > 0 |
| Удар кулаком | `settings.dynamites.punch-ignites: true` |
| Сразу из руки | `settings.dynamites.auto-ignite: true` |

Переопределить режим для конкретного динамита можно ключом `auto-ignite:` в
его файле в `dynamites/` — он главнее глобальной настройки.

Установка идёт через обычный `BlockPlaceEvent`, поэтому защиту приватов QPS
не обойти. Сломанный установленный динамит возвращается предметом с PDC.

## Как динамит ломает блоки

Vanilla кладёт в `blockList` только то, что разрушил бы обычный TNT — обсидиан
туда не попадает никогда. Поэтому судьба блока решается так:

1. **Неразрушимое** (бедрок, порталы, командные блоки) — никогда не трогаем.
2. **Явное правило** `breaking.blocks.<MATERIAL>` — решает только
   `break-chance`, сопротивление не важно. Это единственный корректный способ
   дать динамиту пробить обсидиан (1200).
3. **Потолок** `breaking.max-resistance` — всё, что выше, не трогаем.
4. **Формула** `(resistance + 0.3) * resistance-scale <= power`.
5. **Трансформация** (`transformable-blocks`) важнее ломания: древние обломки
   сначала деградируют в обсидиан.

Отдельно проверяются приваты QPS: ядро (`isCore`) неразрушимо, остальные блоки
ломаются только при флаге `EXPLOSION_DAMAGE`.

```yaml
breaking:
  max-resistance: 1200
  resistance-scale: 0.3
  default-drop-chance: 100
  blocks:
    OBSIDIAN:
      break-chance: 100
      drop-chance: 0     # иначе рейд бесконечно возобновляем
```

## Аудит и архитектура

Подробный разбор ошибок старой версии и описание новой архитектуры —
в [`docs/AUDIT.md`](docs/AUDIT.md).

## Сборка

Требуется JDK 21 и **локально установленный QweProtectStones**.

> ⚠️ `org.qweyns:QweProtectStones:2.0.0` **не публикуется** ни в один публичный
> репозиторий (ни в Maven Central, ни в papermc). Зависимость разрешается
> только из вашего `~/.m2/repository`, поэтому QPS обязательно нужно собрать
> из исходников перед первой сборкой QweTnts.

```bash
# 1. Установить QPS в локальный Maven-репозиторий (ветка main, версия 2.0.0)
cd ../QweProtectionStones
git checkout main && git pull
mvn clean install -DskipTests

# 2. Собрать QweTnts
cd ../QweTnts
mvn clean package
```

Итоговый jar — `target/QweTnts-1.0.0.jar`. В CI (GitHub Actions) то же самое
делает воркфлоу `.github/workflows/build.yml`.

### Сборка в IntelliJ IDEA (без терминала)

В проекте лежат две готовые конфигурации запуска (папка `.run`), поэтому
вручную ничего вводить не нужно:

1. Откройте проект QweTnts: **File → Open…** → папка `QweTnts`.
2. Справа откройте вкладку **Maven** (если её нет: **View → Tool Windows → Maven**)
   и нажмите **⟳ Reload All Maven Projects**.
3. В выпадающем списке конфигураций сверху (рядом с зелёным ▶) выберите
   **«Собрать QweTnts (одна кнопка: QPS + плагин)»** и нажмите **▶ Run**.
   Эта конфигурация сама сначала выполнит установку QweProtectStones
   (шаг «1) Установить QweProtectStones»), а затем соберёт QweTnts.
4. Дождитесь `BUILD SUCCESS` в окне **Run** (две сборки подряд, ~1–2 минуты).
5. Итоговый файл появится в дереве проекта: `QweTnts/target/QweTnts-1.0.0.jar`
   (правой кнопкой → **Open In → File Manager/Explorer**).

> Флаг `-U` больше не нужен: в `pom.xml` для всех репозиториев выставлен
> `updatePolicy=always`, поэтому Maven не держит закешированную ошибку
> «was not found ... during a previous attempt».

### Если IDE/Maven пишет «was not found in https://repo.papermc.io/...»

Maven один раз попытался скачать QPS с papermc, не нашёл и **закешировал
неудачу**. После локальной установки QPS кеш надо сбросить — иначе ошибка
будет повторяться, даже когда артефакт уже лежит в `~/.m2`:

```bash
# Вариант 1 (рекомендуемый): принудительно обновить всё
cd ../QweTnts && mvn -U clean package

# Вариант 2: удалить только запись о QPS
rm -rf ~/.m2/repository/org/qweyns/QweProtectStones
```

В IntelliJ IDEA: после `mvn install` для QPS нажмите **Reload All Maven
Projects** (⟳ в панели Maven). Если зависимость всё ещё красная — включите
**Settings → Build Tools → Maven → Always update snapshots** и перезагрузите
проект, либо выполните `mvn -U clean package` в терминале IDE и снова Reload.

Проверить, что артефакт установлен:

```bash
ls ~/.m2/repository/org/qweyns/QweProtectStones/2.0.0/
# ожидаемо: QweProtectStones-2.0.0.jar  QweProtectStones-2.0.0.pom
```

## Конфигурация QPS (regions.yml)

Чтобы механика работала как на HW Lite, пропишите секции `explosions` у типов приватов:

```yaml
netherite:
  explosions:
    TNT: false
    DYNAMITE_A: false
    DYNAMITE_B: false
    C4: true
    SHOCKWAVE: true
unique:
  start_durability: 4
  explosions:
    C4: true
    SHOCKWAVE: true
iron:
  explosions:
    TNT: true; DYNAMITE_A: true; DYNAMITE_B: true; C4: true; SHOCKWAVE: true
```

Не забудьте включить осаду (`siege.enabled: true`) в `config.yml` QPS.

## Добавление своего динамита

Создайте файл в `plugins/QweTnts/dynamites/myboom.yml` по образцу
`shockwave.yml` из ресурсов, выполните `/qtnt reload`. Пример кастомного ингредиента
(чтобы крафт требовал именно PDC-динамита, а не ванильный TNT):

```yaml
recipe:
  shape:
    - "GSG"
    - "GAG"
    - "GSG"
  ingredients:
    G: GUNPOWDER
    S: SAND
    A:
      custom-type: dynamite_a   # ссылается на name динамита A
```

## Права

| Право                        | По умолчанию | Описание                                          |
|------------------------------|--------------|---------------------------------------------------|
| `qwetnts.use`                | `true`       | Базовое право на использование динамитов (фоллбек)|
| `qwetnts.type.<id>`          | `op`         | Право на конкретный динамит (`c4`, `shockwave` …) |
| `qwetnts.admin`              | `op`         | `/qtnt reload\|list\|give`                       |
| `qwetnts.bypass.world`       | `op`         | Игнорировать запрет миров и радиус спавна         |
| `qwetnts.bypass.region`      | `op`         | Ставить динамиты в чужих приватах                 |
| `qwetnts.bypass.antilag`     | `op`         | Игнорировать кулдаун и лимиты анти-лага           |
| `qwetnts.bypass.raidblock`   | `op`         | Ставить обсидиан на месте рейд-блока              |

## Планы (Фаза 2, по ТЗ)

- Спавнер-майнинг с шансом дропа;
- временный лёд и кумулятивный (направленный) заряд;
- руны на ТНТ и ТНТ-пушка;
- DiscordSRV-алерты и маркеры Dynmap/BlueMap;
- интеграция с Economy (Vault/PlayerPoints);
- крупные взрывы по тикам (заготовка в `settings.anti-lag.large-explosion-*`).
