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
- `stages`: optional step list. See [Quest Steps](#quest-steps).
- `objectives`: typed objective array. Each may name a `stage`.
- `startEvents`: events run when the quest starts.
- `completeEvents`: events run when all objectives complete.
- `rewards`: completion reward events.
- `category`: optional. Files the quest in the Journal and sets the tracker's badge: `story`, `side`,
  `contract`, `guild`, `community`, `daily`, or any word of your own. Without one, the quest is filed
  under "Quests".
- `rewardText`: optional. The rewards players are told about, shown in the Journal and on the board.
  Rewards it does not mention stay hidden.
- `difficulty`, `partySize`: optional. Shown on the quest board.
- `lockedText`: optional. Shows the quest on the board as a locked card, with this text, while the
  player cannot start it. Without it, a locked quest is not shown.

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

`runCommand` defaults to **the player**, so the server re-checks their permissions and skips offline
players. Set `executeAs` to `console` only for trusted content that intentionally needs unrestricted
server permissions. Commands resolve PlaceholderAPI placeholders first and MysticQuests placeholders
second.

```json
{ "type": "runCommand", "command": "spawn" }
```

```json
{ "type": "runCommand", "command": "give %player% hytale:gold_coin 5", "executeAs": "console" }
```

## Unsupported Types

These are rejected at package load with an explanation rather than accepted and then ignored at
runtime. Loading fails loudly so a quest gate never silently reads as "passed" and a reward never
silently vanishes.

| Type | Kind | Reason |
| --- | --- | --- |
| `level` | condition | No level provider is integrated; gate on a variable or tag instead. |
| `custom` | condition and event | Register the type through `MysticQuestsRegistry` and use its id instead. |
| `runForAll` | event | Multi-player fan-out is not implemented; use `party` or target `players`. |
| `runIndependent` | event | Detached execution is not implemented. |
| `packetEffect` | event | Never did anything; use `sendTitle`, `actionBar`, or `setCamera`. |

## Visibility

`hidePlayer`, `showPlayer`, `hideEntity`, and `showEntity` events, the `playerHidden` and
`entityHidden` conditions, and condition-driven `playerHiders` rules are all supported. Visibility is
always pairwise — hidden *from* a particular viewer, never a global flag on the player.

Three details are worth knowing when authoring:

- **Hides stack by source.** An explicit `hidePlayer` event and a `playerHiders` rule can both hold
  the same pair hidden, and the subject stays hidden until both let go. A rule that stops matching
  therefore cannot cancel a hide a quest event applied on purpose.
- **Nameplates follow the entity.** A hidden player's nameplate is suppressed along with their body,
  including plates written by MysticNameTags, which uses the engine's own nameplate component.
- **MysticVanish keeps priority.** Both mods hide players through the same shared engine set, so
  MysticQuests never lifts a hide it did not place, and never lifts one while MysticVanish still
  wants that player hidden. Set `integrations.mysticVanish` to `false` to opt out.

A viewer who has the "show entity markers" client setting enabled is an engine-level exception: its
hide pass returns early for them, so they keep seeing hidden players. MysticQuests logs this once per
viewer rather than letting it read as a quest bug.

Hides are scene state, not save state — everything a player had hidden is released when they
disconnect, and every override is dropped on content reload.

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

### Where a conversation opens

`start` names the node the conversation opens on. It also accepts an **ordered list**, and the first
candidate whose `conditions` pass is the one that opens:

```json
"start": ["after_quest", "during_quest", "hello"]
```

This is how an NPC greets a player differently once something has changed — quest taken, quest
finished, tag set. With a single entry point, conditions on it could only refuse to open the
conversation at all, so the NPC opened on the same line forever.

Order is author order: put the narrowest condition first and an unconditional node last, or the
fallback wins before the specific cases are reached. A conversation whose every candidate fails does
not open at all — still the way to keep an NPC silent until something is true. Every candidate must
name a real node; a typo is a load error rather than an NPC that goes quiet months later.

`"start": "hello"` is unchanged and still means exactly what it did.

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

### MysticGeneration NPC Bindings

When MysticGeneration is installed and `integrations.mysticGeneration` is enabled, conversations may
bind to NPCs authored in its Studio. Two fields are available, checked before the native entity
fields:

- `generationUuid` — one NPC, by the stable identity MysticGeneration assigns at spawn
- `generationDefinition` — every NPC spawned from a definition, e.g. `hyzion:avalon_guard`

Prefer these over `uuid`, `type`, and `name` for generated NPCs. Publishing a definition reloads its
role by removing and re-adding every live entity of that kind, which gives each one a new entity
UUID; a binding written against `uuid` stops matching at that point, while the two fields above
survive it, along with chunk unload and restarts.

```json
{
  "entity": {
    "generationDefinition": "hyzion:avalon_guard",
    "interactionHint": "talk",
    "showPrompt": true
  }
}
```

Interacting with a generated NPC also raises an `interactNpc` objective signal whose target is the
definition id, alongside the usual `interactEntity` signal. An `interactNpc` objective matches either
the definition id or one NPC's stable identity:

```json
{ "id": "greet_guard", "type": "interactNpc", "target": "hyzion:avalon_guard", "amount": 1 }
```

Quests can also spawn and remove generated NPCs. `spawnNpc` takes a definition file name from
MysticGeneration's `definitions/` directory; the definition must already be compiled, and must be
staged or published rather than a draft. With no coordinates the NPC appears in front of the acting
player. `variable` records the new NPC's stable identity in a player variable, which is the only way
to address one specific NPC later when several share a definition.

```json
{ "type": "spawnNpc", "definition": "avalon_guard", "distance": 3, "variable": "escort_npc" }
```

```json
{ "type": "despawnNpc", "target": "generation" }
```

`despawnNpc` removes the NPC and forgets any off-screen record of it, so a despawned NPC does not
return when a player walks back into range. Its `target` accepts the selectors below; the default is
`generation`, the NPC the trigger fired against.

Two target selectors resolve MysticGeneration identities anywhere a `target` is accepted:

- `generation` — the NPC the trigger fired against
- `generation:<definition>` — every live NPC of that definition in the acting player's world

These yield stable identities rather than entity UUIDs, so entity-scope tags and variables written
against them survive a republished definition. Plain `context` still yields the live entity UUID,
which is what visibility and targeting actions need.

`generation:<definition>` only sees NPCs that are currently ticking. One that MysticGeneration has
moved off-screen is not a target until a player brings it back into range, so use it for "every
guard near the action" rather than as a world-wide census.

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

Give notifications that report the same thing over and over a `tag`. The client replaces a toast
that has the same tag instead of stacking a new one, so "Wolves slain 3 / 5" updates in place:

```json
{ "type": "notification", "title": "Thin the Pack", "body": "Wolves slain: %objective.wolves.amount% / %objective.wolves.total%", "tag": "hunt.wolves.progress" }
```

MysticQuests' own announcements (below) use tags beginning `mq.`; pick tags that do not.

### Quest announcements

With `ui.transitionCards` set to `true` in `config.json`, MysticQuests announces the moments of every
quest itself, each its own way:

| Moment | Toast |
| --- | --- |
| Accepted | "Quest accepted: *title*", with the first objective. |
| New step | The quest's title, with "New step \| STEP 2 OF 4 \| *step name*". |
| Progress | The quest's title, with the objective and its count. One live toast per quest, updated in place. |
| Complete | "Quest complete" in the success style, replacing the quest's accepted or step toast if it is still showing. |

Nothing is announced for what a player already had when they joined, so reconnecting never replays a
completion. It is off by default because content that already sends `notification` events at these
moments would show both; turn it on for new content, or remove those events when you do.

Item notifications use the `item` and `quantity` fields instead of `icon`:

```json
{ "type": "notification", "title": "Reward", "body": "+3 Apples", "style": "Success", "item": "hytale:apple", "quantity": 3 }
```

## Chat Messages

Use `sendMessage` events for colored player chat feedback. MysticQuests accepts legacy color markers such as `&a`, `&c`, `&l`, `&o`, `&r`, plus hex colors as `&#RRGGBB`.

```json
{ "type": "sendMessage", "message": "&aQuest started: &#F5C842The Lost Tools" }
```

## Quest Steps

Long quests read badly as one flat list — thirteen objectives do not fit a HUD, and the four that do
tell the player nothing about what comes after. Objectives can therefore be grouped into **steps**.
The HUD shows the current step's objectives plus whole-quest progress; the journal shows every step
as a heading over its objectives.

```json
{
  "id": "new_horizons",
  "displayName": "New Horizons",
  "stages": [
    { "id": "arrival", "displayName": "Speak with the Old One" },
    { "id": "discover", "displayName": "Discover the Realm" },
    { "id": "keepers", "displayName": "Seek the Seven Keepers" },
    { "id": "avalon", "displayName": "The Path to Avalon" }
  ],
  "objectives": [
    { "id": "old_one", "displayName": "Speak with the Old One", "type": "dialogue", "target": "hyzion:old_one", "stage": "arrival" },
    { "id": "vote_crates", "displayName": "Visit the Vote Crates", "type": "reachLocation", "stage": "discover" },
    { "id": "nexus_grounds", "displayName": "Discover the Nexus Portal Grounds", "type": "reachLocation" },
    { "id": "quests_intro", "displayName": "Learn about Quests & Adventures", "type": "dialogue", "target": "hyzion:keeper_quests", "stage": "keepers" },
    { "id": "nexus_gate", "displayName": "Enter Avalon through the Nexus Gate", "type": "triggerEnter", "target": "hyzion:nexus_gate", "stage": "avalon" }
  ]
}
```

The rules:

- **An objective with no `stage` joins the step above it**, the way paragraphs fall under the last
  heading. `nexus_grounds` above belongs to `discover` without repeating it. Objectives listed before
  any stage is named form one leading group, shown as "Objectives".
- **`stages` is optional.** Objectives may name stages the quest never declares; the steps are then
  derived in the order they first appear, and each name is humanised from its id — `citadel_keepers`
  shows as "Citadel Keepers". Declaring `stages` fixes the order and supplies real names.
- **Once `stages` is declared, an objective naming a stage that is not in it fails the load**, since
  that is a typo rather than a new step.
- `"stages": ["arrival", "discover"]` is the short form of the same list of `{ "id": … }` objects.
- Instruction-style packages name a step the same way: `mobkill Bandit 3 name:"Bandits" stage:hunt`.

**Steps are presentation only.** They decide what the player is shown next, not what counts: an
objective in a later step still advances when its signal arrives, and the quest completes when every
objective is done regardless of grouping. A quest that declares no steps at all behaves exactly as it
did before they existed.

The in-game Quest Studio form has no step inputs. It preserves the steps of a quest it saves, but to
edit them use the Package Scripts tab or the package files directly.

## Quest HUD

MysticQuests shows one pinned active quest in a `CustomUIHud` while the player has active quests. If the player has not pinned a quest, the runtime picks the oldest active quest deterministically. Players can control the pin with `/mquest track <quest>` and `/mquest untrack`.

The tracker leads with one **primary objective** — the first open objective of the **current step**
(the first step with an objective still open, or the last step once everything is done) — with its
own progress meter, the step line (`STEP 2 OF 4 | Discover the Realm`) and whole-quest progress
(`QUEST 5 / 13`). It has two compositions:

- **Compact** — title, primary objective, one progress signal and a guidance line.
- **Expanded** — the same plus up to three more of the step's objectives, open ones first.

By default the tracker expands only when the current step has more than one objective. Players can
pin a composition with `/mquest hud <auto|compact|expanded>`. The tracker hides while a
conversation is open, and steps down to compact while a puzzle card is showing. A quest without steps has
one implicit step, so the primary objective is simply its first open objective and there is no step
line.

### Objective map markers

Any objective may carry a `marker`. While that objective is the tracked quest's primary objective,
the player sees it on their world map and the tracker's guidance line points to it.

```json
{ "id": "vote_crates", "displayName": "Visit the Vote Crates", "type": "reachLocation",
  "marker": { "world": "default", "x": 120, "y": 64, "z": -88, "label": "Vote Crates" } }
```

| Field | Meaning |
| --- | --- |
| `world`, `x`, `y`, `z` | Required. The world's name and the marker's position. |
| `label` | What the map calls it. Defaults to the objective's `displayName`. |
| `area` | `true` marks the centre of a region to search, not an exact spot: the map says "Search: …" and the tracker says to search the marked area. |
| `icon` | A file name below the client's `UI/WorldMap/MapMarkers`. Defaults to `Home.png`, the icon vanilla objectives use. |

A marker that cannot be placed — no world, a coordinate that is not a number, an icon that is not a
plain `*.png` file name — fails the reload. Markers are presentation only: reaching the spot does not
advance the objective by itself; the objective's own `type` still decides that. The marker appears
only in the world it names, and goes away once the objective is done or the quest is no longer
tracked. Instruction-style packages cannot express the nested block; write the objective in JSON or
YAML instead.

### Puzzle card

When a player feeds a story puzzle through a trigger volume, a card appears above the hotbar with
the puzzle's name, a hint written for its rule type, its progress and a short reaction ("The
mechanism responds.", "The sequence resets."). It never names inputs, shows which candidates were
dealt, or reveals an order. The card goes a few seconds after the puzzle is solved, or after a minute
without an input. It shows to the player who acted; for a party puzzle it says the progress is
shared.

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

Volume scopes can omit `target` when they run from native trigger-volume objectives or HyExtras trigger context. Volume keys use `worldName:volumeName`, falling back to `volumeName` when the world is unavailable. The name is the one the trigger-volume tool shows; the engine's generated volume id is not used, and a volume without a name falls back to that id.

```json
{ "type": "tag", "scope": "volume", "tag": "ruins_unlocked" }
```

```json
{ "type": "setVariable", "scope": "volume", "key": "visits", "value": "1" }
```

Convenience aliases are also accepted: `globalTag`, `entityTag`, `blockTag`, `volumeTag`, `globalVariable`, `entityVariable`, `blockVariable`, and `volumeVariable`. Player state remains authoritative in MysticQuests. When HyExtras is installed and export is enabled, player tags and variables are mirrored to HyExtras.

## Trigger Volume Integration

Trigger configurations can use MysticQuests conditions and effects directly. MysticQuests converts
the native trigger context into the same target context used by quests, including player, entity,
volume, and block data.

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

Available trigger conditions are `mysticquests:has_tag` and `mysticquests:variable`. Available effects are `mysticquests:add_tag`, `mysticquests:remove_tag`, `mysticquests:set_variable`, `mysticquests:remove_variable`, `mysticquests:increment_variable`, `mysticquests:event`, `mysticquests:action`, `mysticquests:rich_message`, and `mysticquests:run_command`.

Fixed-choice fields such as `Scope`, variable `Operator`, and rich-message `Audience` use editor
dropdowns. Rich messages resolve PlaceholderAPI placeholders first, then MysticQuests script
placeholders, and finally apply legacy `&` formatting and `&#RRGGBB` colors. Global messages resolve
placeholders separately for each online player.

```json
{
  "type": "mysticquests:rich_message",
  "message": "&aWelcome %player%! You have &#F5C842%variable.coins% coins.",
  "audience": "global",
  "package": "tutorial"
}
```

HyExtras volume tags remain separate from MysticQuests volume tags unless a trigger explicitly calls one of these bridge effects.

## Reloads

`/mquest reload` validates every package before replacing the active registry. If any package has duplicate IDs, missing objective IDs, unknown types, or malformed JSON, the old registry stays active.
