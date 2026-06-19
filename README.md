# MysticQuests

Questing system that is tag and variable driven.

A Hytale server mod built with Java.

## V1 Engine

MysticQuests now boots a package-driven V1 runtime:

- Typed JSON quest packages from `plugins/mysticquests/packages/<package-id>/`
- Atomic package reloads through `/mquest reload`
- JSON or SQLite player progress storage
- Active/completed quest state, objective progress, and scoped tags/variables
- Hytale event bridge for craft, interaction, and trigger-volume enter/exit objectives
- Volume-scoped state for trigger-volume scripting
- Always-on pinned quest HUD through native `CustomUIHud`
- Entity-bound conversations with UUID-first matching and type/name fallback
- Native Hytale notifications for quest and reward feedback
- Optional VaultUnlocked economy checks/rewards
- Optional PlaceholderAPI expansion under the `mysticquests` identifier
- Optional HyExtras trigger bridge for MysticQuests state conditions/effects

Default config is generated at `plugins/mysticquests/config.json`:

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
    "hyExtrasExportPlayerState": true
  },
  "state": {
    "migrateLegacyPlayerTags": true,
    "migrateLegacyPlayerVariables": true
  },
  "debug": false
}
```

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

Supported objective types are `kill`, `gather`, `craft`, `triggerEnter`, `triggerExit`, `interactEntity`, `interactObject`, `reachLocation`, `dialogue`, `timer`, and `custom`.

Supported condition types are `tag`, `questCompleted`, `questActive`, `variable`, `level`, `permission`, `economy`, and `custom`.

Scoped condition aliases include `globalTag`, `entityTag`, `blockTag`, `volumeTag`, `globalVariable`, `entityVariable`, `blockVariable`, and `volumeVariable`.

Supported event types are `giveItem`, `removeItem`, `runCommand`, `sendMessage`, `startQuest`, `completeQuest`, `addTag`, `removeTag`, `setVariable`, `removeVariable`, `incrementVariable`, `modifyMoney`, `packetEffect`, `triggerHyExtrasEffect`, `notification`, and `custom`.

## Commands

- `/mquest reload`
- `/mquest start <player-uuid|self> <quest>`
- `/mquest complete <player-uuid|self> <quest>`
- `/mquest progress [player-uuid|self]`
- `/mquest journal [player-uuid|self]`
- `/mquest track <quest>`
- `/mquest untrack`
- `/mquest entity uuid`
- `/mquest entity bind <conversation>`
- `/mquest block uuid`
- `/mquest volume uuid`
- `/mquest volume state <volume> get [key]`
- `/mquest volume tag <volume> <add|remove|has|list> [tag]`
- `/mquest state get <scope> <target> [key]`
- `/mquest state tag <scope> <target> <add|remove|has|list> [tag]`
- `/mquest debug [package|quest|player] [id]`

Command permissions are split by subcommand. Player journal commands use `mysticquests.command.journal`; admin commands use `mysticquests.command.admin.reload`, `mysticquests.command.admin.quest`, `mysticquests.command.admin.entity`, `mysticquests.command.admin.volume`, or `mysticquests.command.admin.debug`. `mysticquests.admin` overrides all admin checks. Volume helpers also accept `mysticquests.command.admin.entity` for builder workflows.

## HyExtras Trigger Bridge

When HyExtras is installed and enabled, MysticQuests registers trigger conditions and effects that read or mutate MysticQuests state:

- Conditions: `mysticquests:has_tag`, `mysticquests:variable`
- Effects: `mysticquests:add_tag`, `mysticquests:remove_tag`, `mysticquests:set_variable`, `mysticquests:remove_variable`, `mysticquests:increment_variable`, `mysticquests:event`

Example HyExtras effect:

```json
{
  "type": "mysticquests:add_tag",
  "scope": "player",
  "tag": "entered_ruins"
}
```

Example HyExtras condition:

```json
{
  "type": "mysticquests:variable",
  "scope": "volume",
  "key": "enabled",
  "operator": "eq",
  "value": "true"
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
