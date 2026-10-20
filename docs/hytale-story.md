# Hytale's own story: breaches, bosses and game flags

Hytale 0.7 tells its Chapter 1 through its own systems rather than a quest log:

- **world events**, such as the goblin breaches that open in the wilds;
- **encounters**, such as the Gobliterator boss fight;
- **instances**, such as the Forgotten Temple and the Guttering Hollows;
- **game flags**, the universe-wide campaign progress the temple's devices write.

MysticQuests can start these, wait for them, and start or unlock quests when they happen. Hytale
stays in charge of them: a breach opened by a quest is the same breach the game opens on its own,
with the same goblins, prefabs and titles.

MysticQuests also installs **Hytale Chapter 1** as a ready-made package. It is written to
`packages/hytale_chapter_1/` the first time MysticQuests starts and is yours to edit after that. See
[its README](../src/main/resources/mysticquests/default-packages/hytale_chapter_1/README.md) for the
story it follows and the quests it contains.

## Starting a breach or a boss fight

| Event | Fields | What it does |
| --- | --- | --- |
| `startGoblinBreach` | `kind` | Opens a goblin breach. `summoned` (the default) opens a pre-temple breach 30 to 60 blocks from the player, and a Goblin Scrapper pack comes through when they walk up. `portal` opens a breach portal into the Guttering Hollows near the player. `first` and `perpetual` start Hytale's own first and recurring breaches, which open near a random player. |
| `startWorldEvent` | `event`, `global` | Starts any world event in the player's world, e.g. `Hunt_Breach`. With `global: true` it switches on that event's global record from the gameplay config, which runs it in every world that lists it. `global` also takes the record's UUID directly. |
| `stopWorldEvent` | `event`, `global` | Stops the event in the player's world, or switches a global one off everywhere. |
| `signalWorldEvent` | `signal` | Sends a world-event signal in the player's world. Every running stage waiting for it continues. `goblin_initial_breach_cleared` closes a pre-temple breach as if its pack had been beaten. |
| `startEncounter` | `encounter`, `x`/`y`/`z` | Places an encounter at the player, or at the coordinates in their world. `Goblin_Initial_Breach` drops a breach pack on the spot. Boss encounters such as `Goblin_Gobliterator_Encounter` expect their arena around them (its spawn points, volumes and difficulty button), so place one where its arena is. |
| `setGameFlag` | `flag`, `value` (1), `mode` | Writes a game flag, as the temple's devices do. `mode: set` overwrites, `raise` only ever raises, `remove` clears it. |

```yaml
startEvents:
  - { type: startGoblinBreach }                       # a breach near this player
  - { type: startGoblinBreach, kind: portal }         # a portal into the Guttering Hollows
  - { type: startWorldEvent, event: Goblin_Breach_First_Event, global: true }
  - { type: setGameFlag, flag: goblin_breach_duration, value: 1, mode: raise }
```

`summoned` and `portal` are MysticQuests' own world events, `MysticQuests_Goblin_Breach` and
`MysticQuests_Goblin_Breach_Portal`, shipped in its asset pack. They are Hytale's
`Goblin_Breach_Prelude_Event` and `Goblin_Breach_First_Event` with two changes:

- they open near the player whose quest started them, not near a random player;
- they leave the universe's breach schedule alone. The portal does not hand over to the recurring
  breaches, and neither breach stops when the temple generator is found.

A world event that uses the location type `MysticQuestsPlayerLocation` is placed near the player
whose quest started it. Any world event in a pack can use this location type.

Two limits to know:

- The gameplay config's `ExclusionRadius` (512 blocks by default) keeps events apart. A breach
  waits while another event has a site that close, and opens once that one closes.
- Both pre-temple breaches send the same signal when their pack dies. If several breaches are
  waiting for it in one world, one cleared pack closes all of them.

## Waiting for them

| Objective | Counts when | Target |
| --- | --- | --- |
| `worldEvent` | an event has a site and the player comes within 96 blocks of it (`phase: detected`, the default), or the event finishes after the player came within 64 blocks of its site (`phase: completed`) | the world-event asset, e.g. `Goblin_Breach_First_Event` |
| `worldEventSignal` | a world-event signal is sent by the player, by an encounter the player is fighting in, or within 64 blocks of the player | the signal id, e.g. `goblin_initial_breach_cleared` |
| `encounter` | the player joins an encounter's fight, as the encounter itself counts its members | the encounter id, e.g. `Goblin_Gobliterator_Encounter` |
| `bossDefeated` | an NPC with that role dies while the player was in the fight: a member of a nearby encounter, within 48 blocks, or the killer | the NPC role it dies in, e.g. `Goblin_Gobliterator_Phase3` |
| `enterInstance` | the player arrives in a world | the instance asset (`Forgotten_Temple_Goblins`, `Portals/Portals_Goblins`) or a world name |
| `gameFlag` | a game flag stands at `level` (1) by `comparator` (`>=`) | the flag, e.g. `goblin_lab_power` |

Every one of these takes its target as `target`, as a `targets` list (any one counts), or under its
own name (`event`, `signal`, `encounter`, `boss`, `instance`, `flag`), which may also be a list.
`"*"` matches anything. A missing target or an unknown `phase` fails the reload.

```yaml
objectives:
  - { id: spot, displayName: Find the breach, type: worldEvent,
      targets: [Goblin_Breach_First_Event, MysticQuests_Goblin_Breach_Portal] }
  - { id: pack, displayName: Beat the breach pack, type: worldEventSignal, signal: goblin_initial_breach_cleared }
  - { id: boss, displayName: Defeat the Gobliterator, type: bossDefeated,
      targets: [Goblin_Gobliterator_Phase3, Goblin_Gobliterator_Phase3_HM] }
```

How the counting works:

- **Hytale's events have no owner.** A breach is a world thing, so MysticQuests credits whoever was
  there. Every nearby player with the objective active gets it, the way the game shares a boss's
  reward with everyone in the fight.
- **A sighting is reported once per player per site.** A breach that moves to a new site can be
  seen again.
- **`completed` needs a site.** An event that never placed anything credits nobody. Vanilla
  breaches end when nobody comes, and so do recurring breaches between portals, so for "beat the
  breach" prefer the signal the pack sends (`worldEventSignal`). For "went through", prefer
  `enterInstance`.
- **Bosses change role between phases.** The Gobliterator is `Goblin_Gobliterator_Phase1`, then
  `_Phase2`, then dies as `_Phase3` (`_HM` on hard mode). Name the role it dies in. A role prefix
  is not enough: `Goblin_Gobliterator_Explosive_Barrel` dies all through the fight.
- **Game flags belong to the universe.** Once anyone has powered the goblin lab, every player's
  `goblin_lab_power` objective completes. Use `interactObject` on the device when each player
  should use it themselves.

## Starting and unlocking quests when they happen

Two quest fields take the same definitions as objectives:

| Field | When a definition matches |
| --- | --- |
| `startOn` | The quest starts, if its start conditions pass. The signal that started it also counts toward its own objectives, so a quest started by spotting a breach opens with "Find the breach" done. |
| `unlockOn` | The quest becomes available. Until then it cannot be accepted, and the board shows it as locked when it has a `lockedText`. `unlockEvents` run once, at the moment it unlocks. |

```yaml
quests:
  the_air_hums:
    startOn:
      - { type: worldEvent, targets: [Goblin_Breach_Prelude_Event, MysticQuests_Goblin_Breach] }
  breach_hunter:
    lockedText: "Spot one of the breaches that keep opening."
    unlockOn:
      - { type: worldEvent, event: Goblin_Breach_Perpetual_Event }
    unlockEvents:
      - { type: notification, title: New quest, body: "Breach Hunter is available on the board." }
```

Triggers can be `kill`, `craft`, `triggerEnter`, `triggerExit`, `interactEntity`, `interactObject`,
`interactNpc`, `dialogue`, `signal`, or any type on this page. A quest that is already active or
completed is not triggered again. An unlock is stored as the player tag
`mysticquests.unlocked.<package>:<quest>`; remove it with `/mquest state tag` to lock the quest
again.

## Conditions

| Condition | Fields | Passes when |
| --- | --- | --- |
| `gameFlag` | `flag`, `level` (1), `comparator` (`>=`) | the flag stands there; `comparator` also takes Hytale's `GreaterThan`, `LessOrEqual` and so on |
| `worldEventActive` | `event`, `anywhere` | an event made from that asset is running in the player's world, or in any world with `anywhere: true` |

```yaml
- type: if
  conditions: [ { type: gameFlag, flag: goblin_lab_generator_found } ]
  then: [ { type: startGoblinBreach } ]     # vanilla's pre-temple breaches have stopped
```

## Hytale 0.7 ids

| What | Ids |
| --- | --- |
| Goblin breaches | `Goblin_Breach_Prelude_Event` (pre-temple, from the world's third day), `Goblin_Breach_First_Event` (opened by the temple generator), `Goblin_Breach_Perpetual_Event` (every thirty minutes after that) |
| Their global records | `b7d9e3a1-6c24-4f5b-8e17-9a3d2c40b8f6` prelude, `e3f1c5d2-4b6a-4f8e-9c3b-1a2b3c4d5e6f` first, `60fb3b60-0403-4475-8478-b71d817aca83` perpetual |
| Signals | `goblin_initial_breach_cleared` (a pre-temple breach pack died) |
| Encounters | `Goblin_Initial_Breach` (breach pack), `Goblin_Gobliterator_Encounter`, `Goblin_Gobliterator_Encounter_Hard` |
| Boss roles | `Goblin_Gobliterator_Phase3`, `Goblin_Gobliterator_Phase3_HM` |
| Instances | `Forgotten_Temple_Goblins` (the temple), `Portals/Portals_Goblins` (the Guttering Hollows) |
| Temple devices | `GoblinLab_Generator`, `GoblinLab_Teleporter`, `GoblinLab_Tinkering_Bench_Recipeset_A` to `_C` |
| Game flags | `goblin_lab_generator_found`, `goblin_lab_power`, `goblin_lab_recipe_a` to `_c`, `goblin_lab_bench`, `goblin_lab_gate`, `goblin_lab_teleporter`, `goblin_breach_duration` |

`/worldevent list` shows the events running in a world, and `/gameflags list` shows the flags.
