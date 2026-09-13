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
- 🔤 **i18n/l10n**: все сообщения вынесены в `lang/ru.yml` и `lang/en.yml`; формат
  строк — **MiniMessage** (Adventure) с автоматической поддержкой legacy-цвета `&c/&l`.
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

## Сборка

Требуется JDK 21 и локально установленный QweProtectStones:

```bash
# Установить QPS в локальный Maven-репозиторий
cd ../QweProtectionStones && mvn install -DskipTests

# Собрать QweTnts
cd ../QweTnts && mvn clean package
```

Итоговый jar — `target/QweTnts-1.0.0.jar`. В CI (GitHub Actions) это делает
автоматически воркфлоу `.github/workflows/build.yml`.

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

| Право                     | По умолчанию | Описание                                            |
|---------------------------|--------------|-----------------------------------------------------|
| `qwetnts.use`             | `true`       | Базовое право на активацию динамита (фоллбек)       |
| `qwetnts.type.<id>`       | `true`       | Право на конкретный динамит (`c4`, `shockwave` ...) |
| `qwetnts.admin`           | `op`         | `/qtnt reload|list|give`                            |

## Планы (Фаза 2, по ТЗ)

- Спавнер-майнинг с шансом дропа;
- временный лёд и кумулятивный (направленный) заряд;
- цепная детонация;
- руны на ТНТ и ТНТ-пушка;
- DiscordSRV-алерты и маркеры Dynmap/BlueMap;
- интеграция с Economy (Vault/PlayerPoints);
- крупные взрывы по тикам (заготовка в `settings.anti-lag.large-explosion-*`).
