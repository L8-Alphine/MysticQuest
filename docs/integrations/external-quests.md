# Quests from other mods: Endgame & QoL and MMO Skill Tree

MysticQuests can show quests that other mods run as if they were its own: in the Journal, and in the
quest tracker at the top right. Two mods are supported:

| Mod | What is mirrored |
|---|---|
| **Endgame & QoL** (6.x) | Story and side quests (Temple Keeper, Korvyn, Vorthak…), and the contracts a player accepted from the contract board. |
| **MMO Skill Tree** (1.7.x) with **ZiggfreedCommon** (2.4.x) | Every quest ZiggfreedCommon's quest engine runs: MMO Skill Tree's onboarding and temple lines, and what its packs add (bounties, mastery, taming, stations). Also every skill level, for conditions. |

**The other mod stays in charge of its quests.** It decides when an objective counts, it pays the
rewards, and its quest givers still offer and take back quests. MysticQuests reads the result about
once a second and shows it. Nothing is copied into MysticQuests' own quest files, so there are no
double rewards, and updating the other mod's quests needs nothing from you.

## What players see

- **One tracker.** MysticQuests' tracker can show a mirrored quest. The other mods' own trackers are
  hidden, so the top right of the screen holds one tracker instead of three stacked ones.
- **Which quest it shows.** A player's own MysticQuests quest, until they pick a mirrored one:
  - tracking a quest in Endgame's journal or MMO Skill Tree's quest book moves the tracker to it
    (accepting an Endgame quest tracks it, so a new Endgame quest shows straight away);
  - pressing **Track quest** on a mirrored quest in the MysticQuests Journal does the same, and also
    tracks it in the other mod so its map markers agree;
  - untracking it, finishing it or abandoning it gives the tracker back to their own quest;
  - with no MysticQuests quest tracked, the mirrored quest they tracked most recently shows.
- **When it is time to hand in**, the tracker leads with what to do next: "Return to Vorthak",
  "Claim it at the contract board", "Claim your reward in the quest book".
- **The Journal** lists mirrored quests in their own sections, **ENDGAME** and **MMO**, with their
  objectives, current step and announced rewards. *Track quest* and *Abandon* work on them and are
  carried out by the mod that runs the quest. Endgame does not allow abandoning story quests; the
  Journal says so.

Words come from the other mod's own translations, in the player's language, the same text its own
journal shows.

## Turning it on

Nothing to do: if Endgame & QoL or MMO Skill Tree is installed, MysticQuests mirrors it.
`/mq integrations` shows what is active. The `externalQuests` section of `config.json`:

```json
"externalQuests": {
  "endgame": true,
  "ziggfreed": true,
  "unifiedTracker": true,
  "hideModTrackers": true,
  "hiddenHuds": [],
  "forwardSignals": true,
  "syncIntervalMillis": 1000
}
```

| Key | Set it to |
|---|---|
| `endgame`, `ziggfreed` | `false` to leave that mod's quests in its own book and tracker only. |
| `unifiedTracker` | `false` to keep MysticQuests' tracker for MysticQuests quests only. The other trackers then stay too. |
| `hideModTrackers` | `false` to keep the other mods' trackers on screen as well. They are only ever hidden while MysticQuests' own HUD (`ui.questHud`) and `unifiedTracker` are on, so nobody is left without a tracker. |
| `hiddenHuds` | Other mods' HUD layers players should never see, by key; a trailing `*` matches a prefix (`"EndgameQoL.*"`). `/mq hud keys [player]` lists the keys a player has. MysticQuests' own layers cannot be hidden. |
| `forwardSignals` | `false` to stop publishing the other mods' moments as signals (below). |
| `syncIntervalMillis` | How often each player is re-read, at least 250. A Journal action re-reads at once. |

Restart the server after changing this section. A HUD key you add is hidden at once for everyone; a
key you remove shows again from each player's next join.

### How hiding works

Every HUD layer reaches the game client by its key. MysticQuests drops the packets that would draw
or update a hidden layer on the way out, and nothing else: the other mod keeps its layer and goes on
updating it, so nothing in it can break, but the client never draws it. Removing a layer is never
blocked. `/mq hud keys` shows hidden layers as *(hidden by MysticQuests)*.

## Using them in your own content

Every mirrored mod adds condition and event types under its id, `endgame` or `ziggfreed`. They work
everywhere a condition or event can go: quest start conditions, rewards, conversation branches,
objective gates and trigger volumes.

### Conditions

| Type | Fields | True when |
|---|---|---|
| `endgame:questCompleted` | `quest` | The player finished that Endgame quest. |
| `endgame:questActive` | `quest` | It is in progress (or waiting to be handed in). |
| `endgame:questReady` | `quest` | Every objective is done and it waits to be handed in. |
| `ziggfreed:questCompleted`, `ziggfreed:questActive`, `ziggfreed:questReady` | `quest` | The same for ZiggfreedCommon quests. |
| `ziggfreed:stat` | `stat`, `min`, `max` | A number the mod reports is in range, such as `level.mining`. |
| `mmo:skillLevel` | `skill`, `level` | The player's MMO Skill Tree level is at least `level`. `skill` is a skill id (`MINING`, `DEFENSE`…), `total` or `combat`. |

Quest ids are the other mod's own: `story_frost_dragon` for Endgame (`Server/Endgame/Quests/*.json`),
contracts as `contract/<id>`. `/mq debug <player>` lists a player's mirrored quests with their ids
under *Mirrored quests*.

### Events

| Type | Fields | Does |
|---|---|---|
| `endgame:acceptQuest` | `quest` | Starts the quest in Endgame as if its giver offered it. Endgame still checks its requirements. |
| `endgame:abandonQuest` | `quest` | Abandons it in Endgame (side quests only). |
| `endgame:trackQuest` | `quest` | Tracks it in Endgame and moves the MysticQuests tracker to it. |
| `ziggfreed:acceptQuest`, `ziggfreed:abandonQuest`, `ziggfreed:trackQuest` | `quest` | The same through ZiggfreedCommon's quest engine (prerequisites, quest log space and cooldowns still apply). |
| `mmo:addXp` | `skill`, `amount` | Gives MMO Skill Tree xp, through its own rules (boosts, level caps, level-up messages). |

### Signals

`signal` objectives count what happens in the other mods. Each moment is sent twice, once naming the
thing and once without, so an objective can ask for "this boss" or "any boss":

| Signal | When | Counts |
|---|---|---|
| `endgame:quest_accepted:<quest>`, `endgame:quest_accepted` | An Endgame quest starts. | 1 |
| `endgame:quest_completed:<quest>`, `endgame:quest_completed` | An Endgame quest is finished. | 1 |
| `endgame:boss_kill:<boss NPC id>`, `endgame:boss_kill` | An Endgame boss dies; every credited player. | 1 |
| `endgame:dungeon_enter:<dungeon>`, `endgame:dungeon_enter` | The player enters an Endgame dungeon. | 1 |
| `endgame:achievement:<id>`, `endgame:achievement` | An Endgame achievement unlocks. | 1 |
| `endgame:combo_tier:<tier>`, `endgame:combo_tier` | A combo climbs to that tier. | 1 |
| `ziggfreed:quest_accepted:<quest>`, `ziggfreed:quest_completed:<quest>` (and without the id) | A ZiggfreedCommon quest starts or is finished. | 1 |
| `ziggfreed:stat:level.<skill>`, `ziggfreed:stat` | A skill level rises (as the mirror sees it). | levels gained |
| `mmo:level_up:<SKILL>`, `mmo:level_up` | An MMO Skill Tree skill levels up. Skills in capitals: `mmo:level_up:MINING`. | levels gained |
| `mmo:xp:<SKILL>`, `mmo:xp` | The player gains MMO Skill Tree xp. | xp gained |

Ids keep the other mod's spelling, since signal names must match exactly. Logging in never replays
anything: quests a player finished before joining do not send signals.

```yaml
quests:
  frost_veteran:
    displayName: Frost Veteran
    category: side
    startConditions:
      - { type: endgame:questCompleted, quest: story_frost_wardens }
    objectives:
      - { id: bosses, displayName: Defeat any three Endgame bosses, type: signal, signal: "endgame:boss_kill", amount: 3 }
      - { id: lich, displayName: Defeat the Frost Lich, type: signal, signal: "endgame:boss_kill:Endgame_Frost_Lich" }
    rewards:
      - { type: mmo:addXp, skill: DEFENSE, amount: 500 }
```

The example package `examples/packages/crossroads_of_orbis` has more.

## Troubleshooting

| Symptom | Check |
|---|---|
| Mirrored quests do not appear | `/mq integrations`. *installed, but this MysticQuests build was made without it*: MysticQuests was built on a machine without the mod in its Mods folder (see below). *does not match*: the mod changed in a way MysticQuests does not know; update MysticQuests. |
| Two trackers on screen | `hideModTrackers`, `unifiedTracker` and `ui.questHud` must all be on. `/mq hud keys` shows which layers are hidden. |
| The tracker shows the wrong quest | Track the one you want in the Journal. Tracking a quest in the other mod's book also moves the tracker. |
| A condition never passes | `/mq debug <player>` shows the ids and states MysticQuests sees for that player. |

## For developers

- Engine-free model and rules: `external/` (`ExternalQuestBook` holds snapshots and the tracker
  choice, `ExternalJournal` turns a mirrored quest into a Journal entry, `ExternalSignals` the diff
  into signals).
- Hytale side: `integration/external/ExternalQuestService` (loading bridges, content types, Journal
  actions), `hytale/ExternalQuestSyncSystem` (the per-player read on the world thread),
  `integration/hud/HudOverrideFilter` (hidden layers).
- Bridges: `integration/endgame/EndgameQuestBridge`, `integration/ziggfreed/ZiggfreedQuestBridge`
  and `MmoSkills`. They compile against the mods' jars, which the build takes from the local Mods
  folder (`hytaleModsPath` in `gradle.properties`, default `%APPDATA%/Hytale/UserData/Mods`). Without
  a jar, its bridge is left out of the build and `/mq integrations` says so. The bridges are loaded by
  name only once their mod is running, so the plugin never fails to start for want of them.
- Another mod can be mirrored by implementing `ExternalQuestBridge` and adding it to
  `ExternalQuestService.KNOWN`.
