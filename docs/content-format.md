# MysticQuests Content Format

MysticQuests keeps everything under `mods/MysticQuests/`, beside the mod jar in the server's mods
directory. Quest content lives in package directories under the configured `packagesPath`, which is
resolved relative to that root and defaults to `packages`.

```text
mods/
  MysticQuests-1.0.1.jar
  MysticQuests/
    config.json
    data/
      mysticquests.db
    packages/
      tutorial/
        package.yml
        quests.json
        conditions.json
        events.json
        variables.json
        conversations.json
```

The mods directory is taken from the location of the loaded jar, so a server started with a custom
`--mods` directory still keeps quest content beside its jar. The resolved absolute path is logged at
startup as `MysticQuests data directory: …` — check that line if content is not being picked up.

IDs are namespaced as `package:id`. Inline references inside the same package may use the local `id`; stored quest state always uses the namespaced ID.

## Editing in game

`/mquest admin` opens Quest Studio for staff with `mysticquests.admin` or
`mysticquests.command.admin.editor`. Its guided builder writes the same format documented below;
advanced arrays accept the typed JSON objects shown in these examples. A publish performs a full
package validation and live reload. If either fails, `quests.json` is rolled back automatically.

The Package Scripts tab edits YAML/JSON package files directly with the same transactional publish.
See [BetonQuest-style scripting](betonquest-style-scripting.md) for manifests, templates, named
elements, quoted instructions, cross-package references, placeholders, schedules, and parties.

The Player State tab works against persisted storage and supports quest grants/completion/reset,
exact objective progress, tracking, abandonment overrides, player tags, and player variables.

## Quest Fields

- `id`: local quest ID.
- `displayName`: player-facing name.
- `description`: journal text.
- `startOnJoin`: optional boolean. When `true`, MysticQuests attempts to start the quest when the player joins.
- `autoStart`: optional string. Use `"playerJoin"` as an alternative to `startOnJoin`.
- `startTriggers`: optional string array. Include `"playerJoin"` to auto-start on join.
- `startConditions`: typed condition array.
- `objectives`: typed objective array.
- `startEvents`: events run when the quest starts.
- `completeEvents`: events run when all objectives complete.
- `rewards`: completion reward events.

## Typed Entries

Conditions, objectives, and events are explicit JSON objects with a `type` field plus type-specific fields.

```json
{ "type": "tag", "tag": "tutorial_started" }
```

Use `notTag` for the common first-join/tutorial gate:

```json
{
  "id": "first_join_tutorial",
  "displayName": "Welcome Tutorial",
  "description": "Starts automatically the first time a player joins.",
  "startOnJoin": true,
  "startConditions": [
    { "type": "notTag", "tag": "welcome_tutorial_complete" }
  ],
  "objectives": [
    { "id": "talk_to_guide", "displayName": "Talk to the guide", "type": "dialogue", "target": "tutorial:guide_intro", "amount": 1 }
  ]
}
```

```json
{ "id": "craft_sword", "type": "craft", "item": "hytale:wooden_sword", "amount": 1 }
```

```json
{ "type": "setVariable", "scope": "player", "key": "quest_stage", "value": "2" }
```

## Composite Conditions

`and`, `or`, and `not` take a nested `conditions` array (a single `condition` object is also
accepted). They are evaluated recursively and may be nested to any depth.

```json
{
  "type": "and",
  "conditions": [
    { "type": "tag", "tag": "arrived_in_town" },
    {
      "type": "or",
      "conditions": [
        { "type": "questCompleted", "quest": "intro" },
        { "type": "permission", "permission": "mysticquests.vip" }
      ]
    }
  ]
}
```

`not` passes when none of its children pass. A composite that declares no children is rejected at
load, because it would otherwise evaluate to a constant.

`permission` checks the player's server permission node and **denies while the player is offline**,
so a gate never opens just because its holder logged off.

## Branching and Grouped Events

`folder` runs a nested `events` list. `if` evaluates `conditions` and runs either `then` or `else`.

```json
{
  "type": "if",
  "conditions": [ { "type": "tag", "tag": "hardmode" } ],
  "then": [ { "type": "giveItem", "item": "hytale:gold_ingot", "amount": 5 } ],
  "else": [ { "type": "giveItem", "item": "hytale:gold_ingot", "amount": 1 } ]
}
```

## Items and Commands

`giveItem` and `removeItem` act on the player's hotbar, storage, and backpack. `giveItem` checks
capacity first and warns the player in chat instead of silently dropping the reward when the
inventory is full.

```json
{ "type": "giveItem", "item": "hytale:wooden_sword", "amount": 1 }
```

`runCommand` dispatches as **the player**, so the server re-checks their permissions. Content files
therefore cannot escalate past what the player could type themselves. Offline players are skipped.

```json
{ "type": "runCommand", "command": "spawn" }
```

## Unsupported Types

These are rejected at package load with an explanation rather than accepted and then ignored at
runtime. Loading fails loudly so a quest gate never silently reads as "passed" and a reward never
silently vanishes.

| Type | Kind | Reason |
| --- | --- | --- |
| `level` | condition | No level provider is integrated; gate on a variable or tag instead. |
| `playerHidden` | condition | Use a `playerHiders` rule; visibility is pairwise rather than a player-only boolean. |
| `custom` | condition and event | No custom provider is registered. |
| `runForAll` | event | Use a real-time schedule for online-player fan-out. |
| `runIndependent` | event | Detached execution is not implemented. |
| `hidePlayer`, `showPlayer` | event | Player visibility control is not implemented. |

## Conversations

Conversations are typed JSON definitions in `conversations.json`. They are UUID-first, with optional type/name fallback for rebuilt or replaced entities.

```json
{
  "conversations": [
    {
      "id": "elder_intro",
      "speaker": "Village Elder",
      "start": "hello",
      "entity": {
        "uuid": "8b348e13-f3df-4d26-8b86-45e7f17c7157",
        "type": "NpcEntity",
        "name": "Elder Rowan",
        "interactionHint": "interactionHints.talk",
        "showPrompt": true
      },
      "nodes": [
        {
          "id": "hello",
          "text": "The road ahead is not safe.",
          "conditions": [],
          "events": [
            { "type": "addTag", "tag": "met_elder" }
          ],
          "choices": [
            {
              "text": "I can help.",
              "conditions": [],
              "events": [
                { "type": "startQuest", "quest": "starter_hunt" }
              ],
              "next": "end"
            }
          ]
        }
      ]
    }
  ]
}
```

Dialogue objectives receive signals for each node as `package:conversation:node`, and for completed conversations as `package:conversation`.

Conversation-bound entities are automatically given Hytale's `Interactable` component for online players on join and after `/mquest reload`, so the normal interaction prompt appears and `PlayerInteractEvent` can open the conversation. The current generic entity API exposes interactability directly; custom per-player hint text is represented in package JSON for future/native NPC prompt integrations.

### HyCitizens NPC Bindings

When HyCitizens is installed and `integrations.hyCitizens` is enabled, conversations may bind directly to HyCitizens citizens. MysticQuests checks bindings in this order: `hyCitizensId`, spawned `uuid`, `name`, `hyCitizensGroup`, then native entity fallback. Matching HyCitizens interactions are cancelled and replaced by the MysticQuests conversation UI; unbound citizens keep their normal HyCitizens behavior.

```json
{
  "entity": {
    "hyCitizensId": "rootling_merchant",
    "hyCitizensGroup": "tutorial",
    "uuid": "spawned-npc-uuid",
    "type": "HyCitizens",
    "name": "Rootling Merchant",
    "interactionHint": "talk",
    "showPrompt": true
  }
}
```

Builder helpers:

- `/mquest hycitizens list [near]`
- `/mquest hycitizens info <id>`
- `/mquest hycitizens bind <conversation> <id>`

## Notifications

Use `notification` events for native Hytale notifications. Supported styles are `Default`, `Success`, `Warning`, and `Danger`.

```json
{
  "type": "notification",
  "title": "Objective Updated",
  "body": "Return to the Village Elder.",
  "style": "Success",
  "icon": "mysticquests/icons/quest.png"
}
```

Item notifications use the `item` and `quantity` fields instead of `icon`:

```json
{ "type": "notification", "title": "Reward", "body": "+3 Apples", "style": "Success", "item": "hytale:apple", "quantity": 3 }
```

## Chat Messages

Use `sendMessage` events for colored player chat feedback. MysticQuests accepts legacy color markers such as `&a`, `&c`, `&l`, `&o`, `&r`, plus hex colors as `&#RRGGBB`.

```json
{ "type": "sendMessage", "message": "&aQuest started: &#F5C842The Lost Tools" }
```

## Quest HUD

MysticQuests shows one pinned active quest in a `CustomUIHud` while the player has active quests. If the player has not pinned a quest, the runtime picks the oldest active quest deterministically. Players can control the pin with `/mquest track <quest>` and `/mquest untrack`.

Up to four objective rows are shown, each with a state glyph, the objective name, and its counter.

## Command Surface

| Command | Surface |
| --- | --- |
| `/quest`, `/quests` | Quest board — quests you can accept right now, with an Accept action. |
| `/journal` | Quest log — Current, Completed, and Abandoned tabs. Track and Abandon act on Current. |
| `/mquest …` | Full command tree, including the admin subcommands. |

The two player commands are aliases of `/mquest` with a default subcommand, so `/quest` and
`/journal` also accept every `/mquest` subcommand. Each one still enforces its own permission.

## Abandoning and Re-accepting

`/mquest abandon <quest>` — or the Abandon button on the journal's Current tab, behind a two-press
confirmation — drops an active quest. Objective progress and quest-scoped variables are discarded;
completion history is untouched. The abandonment is recorded with a timestamp and appears on the
journal's Abandoned tab.

**By default an abandoned quest cannot be taken again.** Authors opt back in per quest:

```json
{
  "id": "bounty_run",
  "abandonCooldownSeconds": 3600,
  "reacceptConditions": [
    { "type": "tag", "tag": "spoke_to_guild_master" }
  ],
  "objectives": [ ... ]
}
```

- `abandonCooldownSeconds` — wait this long after abandoning.
- `reacceptConditions` — a normal condition array that must pass.
- **Both present: both must be satisfied.** For either/or, wrap them in an `or` composite condition.
- **Neither present: the quest is locked permanently once abandoned.**

The gate is enforced in `startQuest`, so it applies to conversation choices, `startQuest` events, and
the quest board equally — not just the UI. Blocked quests are hidden from the board and show their
reason ("Available again in 42m 10s", or the requirement text) on the Abandoned tab.

### Staff override

```bash
/mquest reaccept <player-uuid|self> [quest]
```

With no quest argument it lists that player's abandoned quests, each with its timestamp and whether
it is currently retakeable — use it to find the ID to clear. With a quest argument it deletes the
abandonment record, making the quest immediately acceptable again regardless of cooldown or
re-accept conditions.

Requires `mysticquests.command.admin.quest` (or `mysticquests.admin`). Successful overrides are
written to the server log with the acting staff member's name and UUID.

**It does not restore progress.** Abandoning discards objective progress and quest-scoped variables;
clearing the record only removes the lock, so the quest starts over from zero.

## Scoped State

Tags and variables support `player`, `global`, `entity`, `block`, and `volume` scopes. Missing `scope` keeps the old behavior and means `player`.

```json
{ "type": "tag", "scope": "player", "tag": "tutorial_started" }
```

```json
{ "type": "setVariable", "scope": "global", "key": "festival", "value": "open" }
```

Entity and block scopes can omit `target` when they run from an interaction or conversation bound to the current entity/block.

```json
{ "type": "tag", "scope": "entity", "tag": "elder_intro_complete" }
```

```json
{ "type": "setVariable", "scope": "block", "key": "opened", "value": "true" }
```

Volume scopes can omit `target` when they run from native trigger-volume objectives or HyExtras trigger context. Volume keys use `worldName:volumeId`, falling back to `volumeId` when the world is unavailable.

```json
{ "type": "tag", "scope": "volume", "tag": "ruins_unlocked" }
```

```json
{ "type": "setVariable", "scope": "volume", "key": "visits", "value": "1" }
```

Convenience aliases are also accepted: `globalTag`, `entityTag`, `blockTag`, `volumeTag`, `globalVariable`, `entityVariable`, `blockVariable`, and `volumeVariable`. Player state remains authoritative in MysticQuests. When HyExtras is installed and export is enabled, player tags and variables are mirrored to HyExtras.

## HyExtras Trigger Bridge

When HyExtras is installed, trigger configurations can use MysticQuests conditions and effects. MysticQuests converts HyExtras trigger context into the same target context used by quests, including player, entity, volume, and block data when HyExtras exposes it.

```json
{
  "type": "mysticquests:add_tag",
  "scope": "player",
  "tag": "entered_ruins"
}
```

```json
{
  "type": "mysticquests:variable",
  "scope": "volume",
  "key": "enabled",
  "operator": "eq",
  "value": "true"
}
```

Available HyExtras conditions are `mysticquests:has_tag` and `mysticquests:variable`. Available HyExtras effects are `mysticquests:add_tag`, `mysticquests:remove_tag`, `mysticquests:set_variable`, `mysticquests:remove_variable`, `mysticquests:increment_variable`, and `mysticquests:event`.

HyExtras volume tags remain separate from MysticQuests volume tags unless a trigger explicitly calls one of these bridge effects.

## Reloads

`/mquest reload` validates every package before replacing the active registry. If any package has duplicate IDs, missing objective IDs, unknown types, or malformed JSON, the old registry stays active.
