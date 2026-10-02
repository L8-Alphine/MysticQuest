# MysticQuests

Questing system that is tag and variable driven.

A Hytale server mod built with Java.

Guides: [players](docs/players.md) · [server owners and staff](docs/server-guide.md) · [quest authors](docs/2.0/narrative-runtime.md) · [content format](docs/content-format.md)

## V1 Engine

MysticQuests now boots a package-driven V1 runtime:

- Recursive JSON/YAML quest packages, manifests, and reusable templates
- BetonQuest-style named actions, conditions, objectives, cross-package references, and quoted instructions
- Script placeholders/functions, named items, cancelers, notifications, and real-time schedules
- Shared party objectives/actions through optional MysticRPG and MysticGuilds bridges
- Atomic package reloads through `/mquest reload`
- JSON or SQLite player progress storage
- Active/completed quest state, objective progress, and scoped tags/variables
- Hytale event bridge for craft, interaction, and trigger-volume enter/exit objectives
- Volume-scoped state for trigger-volume scripting
- Split player surfaces: `/quest` quest board to accept, `/journal` quest log for current, completed, and abandoned history
- Abandoned quests are recorded and locked unless the author configures a cooldown or re-accept conditions
- Always-on pinned quest HUD through native `CustomUIHud`
- Entity-bound conversations with UUID-first matching and type/name fallback
- Native Hytale notifications for quest and reward feedback
- Optional VaultUnlocked economy checks/rewards
- Optional PlaceholderAPI expansion under the `mysticquests` identifier
- Optional HyExtras trigger bridge for MysticQuests state conditions/effects
- Optional HyCitizens conversation bridge for NPC-driven quest dialogue
- Optional MysticGeneration bridge binding quests to Studio-authored NPCs by stable identity

## 2.0 Narrative Runtime

The first increment of the [2.0 upgrade](docs/2.0/gap-analysis.md) runs beside the V1 engine:

- Namespaced, typed tags and variables in ten scopes, declared by schema, so typos fail the reload
- Condition trees (`all`, `any`, `none`, `not`, `xor`, `at_least`, `at_most`, `exactly`) with
  impossible/contradiction checks
- Typed actions run as idempotent transitions: rewards are never paid twice, even across restarts
- Player- and party-owned story sessions: persisted, versioned, checkpointed, restored on join
- Per-session, per-player and per-party trigger volume activation over the engine's global switch
- A puzzle engine with ten rule types and persisted random selection (the four-of-ten key hunt)

Existing quests are unaffected, and narrative content can use every V1 event and condition. See
[docs/2.0/narrative-runtime.md](docs/2.0/narrative-runtime.md) and the worked example in
[examples/packages/druid_temple](examples/packages/druid_temple).

Default config is generated at `mods/MysticQuests/config.json`:

```json
{
  "storage": {
    "type": "sqlite",
    "jsonPath": "data/players",
    "sqlitePath": "data/mysticquests.db"
  },
  "packagesPath": "packages",
  "integrations": {
    "vaultUnlocked": true,
    "placeholderApi": true,
    "mysticNameTags": true,
    "hyExtras": true,
    "hyCitizens": true,
    "hyExtrasExportPlayerState": true,
    "mysticGeneration": true
  },
  "state": {
    "migrateLegacyPlayerTags": true,
    "migrateLegacyPlayerVariables": true
  },
  "ui": {
    "questHud": true,
    "hudJoinDelayMillis": 3000
  },
  "debug": false,
  "narrative": {
    "serverId": "default",
    "dataPath": "data/narrative",
    "flushIntervalMillis": 1000,
    "partyExitPolicy": "fork",
    "openNamespaces": [],
    "subtitles": "chat",
    "fallbackLocale": "en-US"
  }
}
```

Give every server on a network its own `narrative.serverId` and never change it once players have
state; see [the narrative configuration](docs/2.0/narrative-runtime.md#1-configuration).
`narrative.subtitles` (`chat`, `title` or `off`) and `narrative.fallbackLocale` control story voice
lines; a v1 conversation node can play one with `"voice": "<media id>"`.

`ui.hudJoinDelayMillis` is how long after a player is ready the quest HUD is pushed. The client is
still registering asset-pack UI documents on the ready tick, and a HUD append that lands in that
window disconnects the player with "Could not find document …" even though the document shipped.
`ui.questHud: false` stops the HUD being pushed at all — worth setting while diagnosing that
disconnect, since a failed HUD append kicks the player rather than degrading.

## Package Format

Packages may use array files or wrapper objects. A minimal quest package:

```json
{
  "quests": [
    {
      "id": "starter_hunt",
      "displayName": "Starter Hunt",
      "description": "Prove yourself near the village.",
      "startConditions": [
        { "type": "tag", "tag": "tutorial_started" }
      ],
      "objectives": [
        { "id": "kill_boars", "type": "kill", "entity": "hytale:boar", "amount": 3 },
        { "id": "enter_gate", "type": "triggerEnter", "volume": "village_gate", "amount": 1 }
      ],
      "rewards": [
        { "type": "addTag", "tag": "starter_hunt_complete" },
        { "type": "modifyMoney", "amount": 25 }
      ]
    }
  ]
}
```

Supported objective types are `kill`, `gather`, `craft`, `triggerEnter`, `triggerExit`, `interactEntity`, `interactObject`, `reachLocation`, `dialogue`, `timer`, `signal`, and `custom`.

A `signal` objective counts a named signal sent by a narrative transition, such as a solved puzzle:
`{ "id": "keys", "type": "signal", "signal": "hyzion:druid_temple.keys_complete" }`. Unlike `custom`,
it matches only its own signal id.

Supported condition types include `tag`, `questCompleted`, `questActive`, `variable`, `permission`,
`economy`, `inConversation`, `inParty`, `partySize`, composites, and named `ref` conditions.

Scoped condition aliases include `globalTag`, `entityTag`, `blockTag`, `volumeTag`, `globalVariable`, `entityVariable`, `blockVariable`, and `volumeVariable`.

Supported event types include inventory, command, message, quest, scoped-state, economy, notification,
conditional/folder, party fan-out, canceler, and named `ref` actions. Unknown/custom types fail package
validation instead of disappearing at runtime.

See [BetonQuest-style scripting](docs/betonquest-style-scripting.md) for YAML packages, templates,
instruction syntax, placeholders, named elements, schedules, cancelers, and party quests.

## Commands

- `/mquest admin` (aliases: `/mquest editor`, `/mquest studio`) — open the in-game Quest Studio
- `/mquest reload`
- `/mquest start <player-uuid|self> <quest>`
- `/mquest complete <player-uuid|self> <quest>`
- `/mquest progress [player-uuid|self]`
- `/mquest journal [player-uuid|self]`
- `/journal`
- `/quest`
- `/quests`
- `/mquest track <quest>`
- `/mquest untrack`
- `/mquest entity uuid`
- `/mquest entity bind <conversation>`
- `/mquest block uuid`
- `/mquest volume uuid`
- `/mquest volume state <volume> get [key]`
- `/mquest volume tag <volume> <add|remove|has|list> [tag]`
- `/mquest hycitizens list [near]`
- `/mquest hycitizens info <id>`
- `/mquest hycitizens bind <conversation> <id>`
- `/mquest state get <scope> <target> [key]`
- `/mquest state tag <scope> <target> <add|remove|has|list> [tag]`
- `/mquest debug [package|quest|player] [id]`
- `/mquest narrative <sessions|puzzle|trigger|validate> ...` — inspect story sessions, puzzles and
  trigger activation; resets and overrides need `mysticquests.command.admin.narrative` and are audited
- `/mq visibility bypass [on|off]` — staff see everyone quests hide from them, without changing any
  quest state or what other players see (`mysticquests.visibility.bypass`;
  `mysticquests.visibility.bypass.always` holds it on from join)
- `/mq visibility status [player]` — bypass state and every quest hide affecting that viewer, with
  its reasons (including MysticVanish)

- `/mq integrations` — every optional integration as active, partial, disabled or absent, and what
  degrades without it (`mysticquests.command.admin.debug`)

`/mq` is a short form of `/mquest` for every subcommand.

Mods that draw their own per-viewer presentation of players (MysticNameTags glyph nameplates, for
example) should ask `MysticQuestsApi#canSee(viewer, target)` and show the player only when every
system they consult allows it; see [Phase 5 notes](docs/2.0/gap-analysis.md#phase-5-notes).

Command permissions are split by subcommand. Player journal commands use `mysticquests.command.journal`; Quest Studio uses `mysticquests.command.admin.editor`; other admin commands use `mysticquests.command.admin.reload`, `mysticquests.command.admin.quest`, `mysticquests.command.admin.entity`, `mysticquests.command.admin.volume`, or `mysticquests.command.admin.debug`. `mysticquests.admin` overrides all admin checks. Volume helpers also accept `mysticquests.command.admin.entity` for builder workflows.

## In-game Quest Studio

Run `/mquest admin` as a player with `mysticquests.admin` or
`mysticquests.command.admin.editor`.

- **Quest Builder** provides guided identity fields and four common objective rows. Additional
  objectives, nested conditions, start/completion events, rewards, and re-accept rules remain fully
  available through the advanced JSON array fields.
- **Validate + Publish** updates `packages/<package>/quests.json` and reloads live content. Publishing
  is transactional: if JSON or package validation fails, the previous file is restored and the
  server keeps its last valid quest set.
- **Player State** accepts an online player name, UUID, or `self`. Staff can start, complete,
  abandon, reset, track, or untrack quests; set exact objective progress; clear abandonment;
  and add/remove player tags and variables.
- **Load Existing** opens any current quest by package ID and local quest ID for editing.
- **Package Scripts** edits any package `.yml`, `.yaml`, or `.json` file in game. It validates and
  reloads the entire content graph transactionally, restoring the previous file on failure.

Quest resets remove active, completed, abandoned, tracked, and quest-variable state for the chosen
quest. This is deliberately explicit and does not erase unrelated player data.

Chat output supports legacy color markers in configured quest messages, including `&a`, `&c`, `&l`, `&o`, `&r`, and `&#RRGGBB`.

## HyCitizens Bridge

When HyCitizens is installed, enabled, and starts cleanly, MysticQuests listens for HyCitizens citizen interactions. If a citizen matches a MysticQuests conversation, MysticQuests cancels the HyCitizens interaction and opens the MysticQuests conversation UI; unbound citizens keep their normal HyCitizens behavior.

Conversation bindings can target HyCitizens metadata:

```json
{
  "entity": {
    "hyCitizensId": "rootling_merchant",
    "hyCitizensGroup": "tutorial",
    "uuid": "spawned-npc-uuid",
    "type": "HyCitizens",
    "name": "Rootling Merchant",
    "interactionHint": "talk"
  }
}
```

## MysticGeneration Bridge

When MysticGeneration is installed and `integrations.mysticGeneration` is enabled, quests can address
NPCs authored in its Studio by the identity MysticGeneration gives them, rather than by entity UUID
or display name. That matters because publishing a definition reloads its role by removing and
re-adding every live NPC of that kind, which changes their entity UUIDs; the generation identity
survives that, along with chunk unload and restarts.

```json
{
  "entity": {
    "generationDefinition": "hyzion:avalon_guard"
  }
}
```

The bridge adds:

- Conversation bindings on `generationDefinition` and `generationUuid`
- An `interactNpc` objective type, matching a definition id or one NPC's stable identity
- `spawnNpc` and `despawnNpc` quest actions, run through MysticGeneration's own spawn path
- `generation` and `generation:<definition>` target selectors

MysticGeneration is not a compile-time dependency: the bridge is reflective, binds lazily so mod
start order does not matter, and stays dormant when the mod is absent. See
[docs/content-format.md](docs/content-format.md) for the authoring details.

## Trigger Volume Integration

MysticQuests registers native trigger-volume conditions and effects that read or mutate MysticQuests state:

- Conditions: `mysticquests:has_tag`, `mysticquests:variable`, `mysticquests:trigger_enabled`, `mysticquests:puzzle_input_available`
- Effects: `mysticquests:add_tag`, `mysticquests:remove_tag`, `mysticquests:set_variable`, `mysticquests:remove_variable`, `mysticquests:increment_variable`, `mysticquests:event`, `mysticquests:action`, `mysticquests:rich_message`, `mysticquests:run_command`, `mysticquests:puzzle_input`, `mysticquests:puzzle_reset`, `mysticquests:trigger_state`

The narrative types make a volume per-audience without switching it for everyone; see
[Trigger volumes](docs/2.0/narrative-runtime.md#26-trigger-volumes).

Example trigger effect:

```json
{
  "type": "mysticquests:add_tag",
  "scope": "player",
  "tag": "entered_ruins"
}
```

Example trigger condition:

```json
{
  "type": "mysticquests:variable",
  "scope": "volume",
  "key": "enabled",
  "operator": "eq",
  "value": "true"
}
```

Commands can run with the triggering player's permissions or with unrestricted console permissions:

```json
{
  "type": "mysticquests:run_command",
  "command": "give %player% hytale:gold_coin 5",
  "executeAs": "console",
  "package": "tutorial"
}
```

Rich messages accept MysticQuests and PlaceholderAPI percent placeholders plus `&` formatting and
hex colors. Set `Audience` to `player` for the triggering player or `global` for all online players:

```json
{
  "type": "mysticquests:rich_message",
  "message": "&aWelcome %player%! You have &#F5C842%variable.coins% coins.",
  "audience": "global",
  "package": "tutorial"
}
```

HyExtras volume tags remain HyExtras-owned. MysticQuests volume tags and variables are separate state unless a trigger explicitly bridges into MysticQuests.

## PlaceholderAPI

When PlaceholderAPI is installed, MysticQuests registers:

- `%mysticquests_active_count%`
- `%mysticquests_current_quest%`
- `%mysticquests_quest_status_<quest>%`
- `%mysticquests_objective_progress_<quest>_<objective>%`
- `%mysticquests_tag_<tag>%`
- `%mysticquests_var_player_<key>%`

## Building

```bash
./gradlew shadowJar
```

The output JAR will be in `build/libs/`.

## Deploying

```bash
./gradlew deployMod
```

Builds the fat JAR and copies it to the Hytale server `mods/` folder.

## Running

Use the included run configurations in your IDE:

- **Run Hytale Server** — Builds, deploys, and starts the server
- **Debug Hytale Server** — Same as Run, with remote debugger on port 5005
- **Build Mod** — Compiles without deploying or starting the server
