# Creating Quests by Hand

This guide is for server admins who write quests directly as files, without the web editor (which is
not built yet). It covers where files go, how the nested folder system works, how a quest is put
together, and how tags, variables and conditions work and where to use them.

Related pages: [server guide](server-guide.md) for installing and permissions,
[content format](content-format.md) for the full field reference, and
[narrative runtime](2.0/narrative-runtime.md) for 2.0 story features (puzzles, story NPCs, voice,
cutscenes).

**Contents:** [The workflow](#the-workflow) · [Folders and packages](#folders-and-packages) ·
[IDs and references](#ids-and-references) · [Anatomy of a quest](#anatomy-of-a-quest) ·
[Objectives](#objectives) · [Events](#events) · [Tags](#tags) · [Variables](#variables) ·
[Conditions](#conditions) · [Named, reusable pieces](#named-reusable-pieces) ·
[Conversations](#conversations) · [Parties](#parties) · [2.0 story content](#20-story-content) ·
[A complete example](#a-complete-example) · [Testing](#testing-a-quest) ·
[Common mistakes](#common-mistakes)

---

## The workflow

1. Write or edit files under `mods/MysticQuests/packages/`.
2. Run `/mquest reload` in game or from the console.
3. If anything is wrong, the reload is refused and every error is listed with its file and location.
   The server keeps running the previous content, so a mistake never takes quests offline.
4. Test the quest on yourself (see [Testing a quest](#testing-a-quest)).

Files can be JSON (`.json`) or YAML (`.yml`, `.yaml`), and you can mix both in one package. YAML is
easier to write by hand, so most examples below use it; the JSON equivalent has the same keys.

> The in-game Quest Studio (`/mquest admin`, **Package Scripts** tab) edits these same files with a
> validate-and-publish button. Everything in this guide applies to it too.

---

## Folders and packages

A **package** is a folder of quest content. It is the unit of organisation and the first half of
every stored quest ID, so choose package names you will keep.

```text
mods/MysticQuests/
  config.json
  templates/                    shared defaults packages can pull in (optional)
  packages/
    avalon/                     package "avalon" (every top-level folder is a package)
      package.yml               optional manifest
      quests.yml
      conversations.yml
      main_story/               plain subfolder: still part of "avalon"
        chapter1.yml
        chapter2.yml
      side_quests/              has its own package.yml, so it is a child package
        package.yml             package "avalon/side_quests"
        fishing.yml
        hunting/
          wolves.yml            part of "avalon/side_quests"
```

The rules:

- **Every folder directly inside `packages/` is a package**, with or without a `package.yml`.
- **A deeper folder becomes its own package only if it contains a `package.yml`** (or
  `package.yaml` / `package.json`). Its ID is its path: `avalon/side_quests`.
- **Any other subfolder is just organisation.** Its files belong to the nearest package above it,
  so you can split a big package into as many folders and files as you like.
- A child package's files never leak into the parent, and the parent's never leak into the child.
- **File names do not matter** when the file is an object with section keys (see below). Use names
  that help you find things.
- Files with `.mq-staging` or `.mq-rollback` in the name are ignored (the Quest Studio uses them).

### What goes in a file

A content file is an object whose top-level keys are **sections**:

```yaml
quests:
  ...
conversations:
  ...
conditions:
  ...
```

| Section | Holds |
|---|---|
| `quests` | Quest definitions. |
| `objectives` | Named objectives quests can reuse. |
| `conditions` | Named conditions. |
| `events` (or `actions`) | Named events. |
| `conversations` | NPC dialogue trees. |
| `items` | Named item shortcuts for `giveItem` / `removeItem`. |
| `notifications` | Reusable notification styles. |
| `cancelers` | "Give up / reset" bundles run by `/mquest cancel` or `cancelQuest`. |
| `schedules` | Daily or cron-timed events. |
| `functions`, `constants` | Text helpers for placeholders. |
| `playerHiders` | Rules that hide players from each other by condition. |
| `variableSchemas`, `tagSchemas`, `puzzles`, `overlays`, `speakers`, `media`, `cutscenes` | 2.0 story content ([below](#20-story-content)). |

A file can also be a **bare array**. Its section is then taken from the file name, so
`quests.json` containing `[ {...}, {...} ]` is read as the `quests` section. This works for
`quests`, `conditions`, `events`, `actions`, `objectives`, `conversations`, `items`, `cancelers`,
`schedules`, `functions`, `notifications`, `constants`, `puzzles`, `overlays`, `speakers`, `media`,
`cutscenes`, `variableSchemas` and `tagSchemas`.

A section can be a **list** of entries with `id` fields, or a **map** keyed by id:

```yaml
quests:                         # list form
  - id: wolves
    displayName: Wolf Trouble

quests:                         # map form: the key is the id
  wolves:
    displayName: Wolf Trouble
```

### How files are combined

All the files of one package are merged into one document, in alphabetical order of their paths:

- The same section in several files is combined. `chapter1.yml` and `chapter2.yml` can both have a
  `quests:` section.
- **If two files define the same ID in one package, the later file wins** (alphabetical by path).
  Keep each ID in one place; duplicates across packages are fine because the package name differs.

### `package.yml`

Optional. All keys are optional:

```yaml
enabled: true          # false skips the whole package (and stops its quests from loading)
version: 1.2.0         # shown in diagnostics and stamped on 2.0 story sessions
templates: [adventure_defaults]
```

### Templates

A **template** is a package under `mods/MysticQuests/templates/` that other packages pull in with
`templates:`. Its content is merged in first and the package's own files override it. Use templates
for things many packages share, such as notification styles, constants or common conditions.
Templates can list templates of their own; the first template listed wins where two overlap.

---

## IDs and references

Every quest, condition, event, objective and conversation has a **local ID** (`wolves`). It is
stored as **package:id** (`avalon:wolves`, or `avalon/side_quests:fishing` for a child package).

When you refer to something:

| You write | It means |
|---|---|
| `wolves` | `wolves` in the same package. |
| `avalon:wolves` | The stored ID, from anywhere. |
| `avalon-side_quests>fishing` | Cross-package shorthand: the dash becomes a folder separator, giving `avalon/side_quests:fishing`. |

> **Do not put dashes in package folder names.** The cross-package shorthand turns them into
> folder separators. Use underscores.

> **Renaming an ID or moving a quest to another package changes its stored ID.** Players' progress
> is saved against the stored ID, so a renamed quest looks brand new to everyone. Pick IDs once;
> if you must rename, reset or migrate the affected players deliberately.

IDs should be lower case with underscores: `ancient_ruins`, `talk_to_elder`.

---

## Anatomy of a quest

```yaml
quests:
  wolves:
    displayName: Wolf Trouble                 # shown to players
    description: The farmer's sheep keep vanishing.
    startConditions:                          # all must pass to start
      - type: tag
        tag: met_farmer
    stages:                                   # optional: group objectives into steps
      - { id: hunt, displayName: Thin the Pack }
      - { id: report, displayName: Report Back }
    objectives:
      - id: kill_wolves
        displayName: Defeat wolves
        type: kill
        target: Wolf_Black                   # NPC role name
        amount: 5
        stage: hunt
      - id: talk_farmer
        displayName: Tell the farmer
        type: dialogue
        target: avalon:farmer_thanks
        stage: report
    startEvents:                              # run when the quest starts
      - type: sendMessage
        message: "&eThe farmer looks worried."
    completeEvents:                           # run when every objective is done
      - type: addTag
        tag: wolves_done
    rewards:                                  # also run on completion
      - type: giveItem
        item: hytale:gold_ingot
        amount: 3
```

| Field | Purpose |
|---|---|
| `id` | Local ID (filled in automatically in map form). |
| `displayName`, `description` | What players see on the board, journal and HUD. |
| `startConditions` | Conditions that must all pass before the quest can start. |
| `startOnJoin: true` | Try to start it every time a player joins (use with a `notTag` gate). |
| `objectives` | What the player must do. The quest completes when all are done. |
| `stages` | Optional steps; the HUD shows the current step's objectives. |
| `startEvents` | Events run when the quest starts. |
| `completeEvents`, `rewards` | Events run when the quest completes. |
| `abandonCooldownSeconds`, `reacceptConditions` | Whether and when an abandoned quest can be taken again. Without either, an abandoned quest stays locked. |

How quests start: from the quest board (`/quest`), from a conversation choice
(`startQuest` event), from any other event list, automatically on join (`startOnJoin`), or by staff
(`/mquest start <player> <quest>`). Every route checks `startConditions`.

---

## Objectives

Each objective has an `id`, a `type`, usually a `target`, and an `amount` (default 1).

| Type | Counts when the player... | `target` is |
|---|---|---|
| `kill` | lands the killing blow on a matching creature or NPC | its NPC role (`Wolf_Black`), MysticGeneration definition (`hyzion:avalon_guard`) or model ID |
| `gather` | is holding enough of an item | the item ID, e.g. `Plant_Crop_Wheat_Item` |
| `craft` | crafts with a matching recipe | the **recipe** ID |
| `interactEntity` | interacts with a matching entity | the entity ID |
| `interactObject` | interacts with a matching block or object | the block or object ID |
| `interactNpc` | talks to a MysticGeneration NPC | its definition (`hyzion:avalon_guard`) or one NPC's stable ID |
| `dialogue` | reaches a conversation, or a node in one | `package:conversation` or `package:conversation:node` |
| `triggerEnter` / `triggerExit` | enters or leaves a trigger volume | the volume key, `world:volumeId` |
| `signal` | a 2.0 story transition sends that signal | the signal ID (field `signal`) |

### How kills are counted

- Only the player who lands the **killing blow** gets the kill, the same rule MysticRPG uses to award
  experience. Arrows and thrown weapons count for whoever fired them. Deaths from falls, the
  environment or other mobs count for nobody.
- Players are never counted, only creatures and NPCs.
- The `target` must match exactly. Use the **NPC role name**: `/npc role` on the creature shows it
  (needs world-editor access), and the game's role files are named the same way
  (`Server/NPC/Roles/.../Wolf_Black.json`). One objective matches one name, so it counts one
  variant: `Wolf_Black` and `Wolf_White` are separate roles.
- For MysticGeneration NPCs, the definition ID works too and survives the NPC being republished.
- `shared: true` lets a kill count for the whole party.
- Story NPCs that belong to someone else's story can't be hit by you, so they can't be killed for
  your objective either.

### How gathering is counted

`gather` works like Hytale's own gather quests: progress is **how many of the item the player is
holding** (hotbar and inventory), capped at `amount`, and it updates every time their inventory
changes.

- Picked up, crafted, traded or given by another quest: it all counts, because only the held count
  matters. Items already in the inventory count as soon as the quest starts.
- Dropping, using or selling the items lowers progress again, until the quest completes.
- The quest does not take the items. To collect them, add a `removeItem` to `completeEvents`, or do
  the hand-in in a conversation choice.
- The item ID must match exactly (the ID from the game's item assets, not the display name).

```yaml
objectives:
  - { id: wheat, displayName: Bring wheat, type: gather, target: Plant_Crop_Wheat_Item, amount: 10 }
completeEvents:
  - { type: removeItem, item: Plant_Crop_Wheat_Item, amount: 10 }
```

Extras:

- `stage: hunt` puts the objective in a step. An objective without `stage` joins the step above it.
- `shared: true` lets party members' progress count for each other (see [Parties](#parties)).
- `displayName` is the line shown on the HUD and journal.

---

## Events

Events (also called actions) are the "do something" entries: in `startEvents`, `completeEvents`,
`rewards`, conversation nodes and choices, cancelers, schedules, and inside other events.

| Event | Fields | Does |
|---|---|---|
| `sendMessage` | `message` | Chat message; `&a`-style and `&#RRGGBB` colours work. |
| `notification` | `title`, `body`, `style`, `icon` or `item` | Native notification. |
| `sendTitle` | `title`, `subtitle`, `duration`, `fadeIn`, `fadeOut` | Big on-screen title. |
| `actionBar` | `message` | Short text above the hotbar. |
| `giveItem` / `removeItem` | `item`, `amount` | Changes the player's inventory. |
| `runCommand` | `command`, `executeAs` (`player` default, or `console`) | Runs a command. |
| `startQuest` / `completeQuest` | `quest` | Starts or completes another quest. |
| `addTag` / `removeTag` | `tag`, `scope` | See [Tags](#tags). |
| `setVariable` / `incrementVariable` / `removeVariable` | `key`, `value` or `amount`, `scope` | See [Variables](#variables). |
| `if` | `conditions`, `then`, `else` | Branches. |
| `folder` | `events` | Runs a group of events. |
| `party` | `events` | Runs the events for every party member. |
| `ref` | `ref` | Runs a [named event](#named-reusable-pieces). |
| `modifyMoney` | `amount`, `account` | Economy (needs VaultUnlocked). |
| `cancelQuest` | `canceler` | Runs a canceler. |
| `spawnNpc` / `despawnNpc` | see content format | MysticGeneration NPCs. |
| `hidePlayer`, `showPlayer`, `hideEntity`, `showEntity` | target fields | Per-viewer visibility. |
| `setCamera` | `mode`, `locked` | Camera mode. |
| `narrative` | `actions`, `story`, `audience` | Runs 2.0 script; see [writing 2.0 stories](2.0/story-scripting.md#the-narrative-event-and-condition). |

Branching example:

```yaml
- type: if
  conditions:
    - { type: tag, tag: hard_mode }
  then:
    - { type: giveItem, item: hytale:gold_ingot, amount: 5 }
  else:
    - { type: giveItem, item: hytale:gold_ingot, amount: 1 }
```

Text in events can use placeholders: `%player%`, `%tag.name%`, `%variable.key%`,
`%quest.id%`, `%objective.id.left%`, `%constant.name%` and more (see
[scripting](betonquest-style-scripting.md#placeholders-and-text)).

---

## Tags

A **tag** is a named on/off flag: the player either has `met_farmer` or does not. Tags are the
simplest way to remember that something happened.

### Setting and clearing

```yaml
- { type: addTag, tag: met_farmer }
- { type: removeTag, tag: met_farmer }
```

### Checking

```yaml
- { type: tag, tag: met_farmer }        # passes if the player has it
- { type: notTag, tag: met_farmer }     # passes if they do not
```

### Scope: whose tag is it?

Add `scope` to put the tag on something other than the player:

| Scope | Belongs to | Example use |
|---|---|---|
| `player` (default) | the player | "this player has met the farmer" |
| `global` | the whole server | "the festival has started" for everyone |
| `entity` | an entity (NPC, mob) | "this NPC has given its reward" |
| `block` | a block | "this chest has been opened" |
| `volume` | a trigger volume | "these ruins are unlocked" |

```yaml
- { type: addTag, scope: global, tag: festival_open }
- { type: tag, scope: entity, tag: reward_given }
```

`entity`, `block` and `volume` scopes use the thing the event was triggered by (the NPC talked to,
the block clicked, the volume entered), so `target` can usually be left out. Shortcuts:
`globalTag`, `entityTag`, `blockTag`, `volumeTag` as the event or condition type.

### Avoiding clashes between packages

Tags are plain names shared by every package, so two packages that both use `completed` collide.
Either prefix them yourself (`avalon_wolves_done`) or add `packageScoped: true` to have the package
name added for you (`avalon.wolves_done`), on both the event and the condition:

```yaml
- { type: addTag, tag: wolves_done, packageScoped: true }
- { type: tag, tag: wolves_done, packageScoped: true }
```

### Common patterns

```yaml
# First-join tutorial that only ever runs once
quests:
  tutorial:
    startOnJoin: true
    startConditions:
      - { type: notTag, tag: tutorial_done }
    completeEvents:
      - { type: addTag, tag: tutorial_done }

# An NPC that greets you differently once you have met
conversations:
  farmer:
    start: [again, first]            # first candidate whose conditions pass opens
    nodes:
      - id: again
        conditions: [ { type: tag, tag: met_farmer } ]
        text: "Back again?"
      - id: first
        text: "Hello, stranger."
        events: [ { type: addTag, tag: met_farmer } ]
```

---

## Variables

A **variable** stores a value under a name: a counter, a choice, a stage name. Values are stored as
text; numbers are compared as whole numbers.

### Setting, counting, clearing

```yaml
- { type: setVariable, key: chosen_side, value: rebels }
- { type: incrementVariable, key: wolves_reported, amount: 1 }    # amount may be negative
- { type: removeVariable, key: chosen_side }
```

### Checking

```yaml
- { type: variable, key: chosen_side, value: rebels }                      # equals (default)
- { type: variable, key: wolves_reported, operator: gte, value: 3 }        # 3 or more
- { type: variable, key: chosen_side, operator: exists }                   # has any value
```

| `operator` | Passes when |
|---|---|
| `eq` (default) | equal |
| `ne` | not equal (or not set) |
| `gt`, `gte`, `lt`, `lte` | greater, greater or equal, less, less or equal (as numbers) |
| `exists` | set to anything |

The symbols `!=`, `>`, `>=`, `<`, `<=` also work.

### Scope

Variables take the same `scope` values as tags (`player` by default, `global`, `entity`, `block`,
`volume`), plus one more:

| Scope | Belongs to | Notes |
|---|---|---|
| `quest` | this player, inside one quest | Cleared when the quest is abandoned or reset. Good for per-run counters. Name the quest with `quest:` when used outside that quest. |

```yaml
- { type: incrementVariable, scope: quest, key: nests_burned }
- { type: variable, scope: global, key: festival_day, operator: gte, value: 2 }
```

Show a variable in text with `%variable.key%`.

### Tags or variables?

Use a **tag** for yes/no facts ("met the farmer"). Use a **variable** when there is a value: a
count, a choice between options, a stage name.

---

## Conditions

A **condition** is a yes/no test. You use them in:

- `startConditions` and `reacceptConditions` on quests,
- `conditions` on conversation nodes and choices (and `start` candidates),
- `if` events,
- `playerHiders` and cancelers,
- [named conditions](#named-reusable-pieces) you reuse.

A list of conditions passes only when **every** entry passes.

| Condition | Fields | Passes when |
|---|---|---|
| `tag` / `notTag` | `tag`, `scope` | the tag is / is not set |
| `variable` | `key`, `operator`, `value`, `scope` | the comparison holds |
| `questCompleted` | `quest` | the player has finished that quest |
| `questActive` | `quest` | the player has that quest in progress |
| `permission` | `permission` | the player has the permission (always fails while offline) |
| `economy` | `amount`, `account` | the player has at least that much money |
| `inParty` | | the player is in a party |
| `partySize` | `amount`, `operator` | the party size compares (default `>=`) |
| `inConversation` | | the player is in a conversation |
| `nearEntity`, `playerHidden`, `entityHidden`, `targetingPrevented` | see content format | proximity and visibility checks |
| `and` / `or` / `not` | `conditions` | all / any / none of the nested conditions pass |
| `ref` | `ref` | a [named condition](#named-reusable-pieces) passes |
| `narrative` | `condition`, `story` | a 2.0 condition passes; see [writing 2.0 stories](2.0/story-scripting.md#the-narrative-event-and-condition) |

Combining:

```yaml
startConditions:
  - type: or
    conditions:
      - { type: questCompleted, quest: wolves }
      - type: and
        conditions:
          - { type: tag, tag: met_farmer }
          - { type: permission, permission: server.vip }
```

`and`, `or` and `not` can nest to any depth. An `and`/`or`/`not` with no children is a load error.

---

## Named, reusable pieces

Instead of repeating the same condition or event in many places, name it once in the package's
`conditions`, `events` and `objectives` sections and refer to it by name:

```yaml
conditions:
  met_farmer: { type: tag, tag: met_farmer }
  veteran:
    type: and
    conditions:
      - { type: questCompleted, quest: wolves }
      - { type: questCompleted, quest: bandits }

events:
  thank_player: { type: sendMessage, message: "&aThe village thanks you, %player%." }

objectives:
  find_camp: { type: triggerEnter, target: avalon:bandit_camp, displayName: Find the bandit camp }

quests:
  bandits:
    startConditions: [met_farmer]          # a name instead of an inline condition
    objectives: [find_camp]
    rewards: [thank_player]
```

Inside a condition or event list, `{ type: ref, ref: veteran }` does the same. Names follow the
[ID rules](#ids-and-references), so `shared>met_farmer` reaches into another package.

Named entries can also be written as one-line instructions, for example
`met_farmer: "tag met_farmer"` or `reward: "giveItem hytale:gold_ingot 3"`; see
[scripting](betonquest-style-scripting.md#named-scripting-elements).

---

## Conversations

```yaml
conversations:
  farmer_thanks:
    speaker: Farmer Bram
    start: hello
    entity:
      generationDefinition: hyzion:farmer        # or uuid / hyCitizensId / name
    nodes:
      - id: hello
        text: "You did it! The sheep are safe."
        events:
          - { type: addTag, tag: farmer_thanked }
        choices:
          - text: "Happy to help."
            next: end
          - text: "Anything else?"
            conditions: [ { type: questCompleted, quest: wolves } ]
            events: [ { type: startQuest, quest: bandits } ]
            next: end
```

- `start` can be a list; the first node whose conditions pass opens.
- A choice with `next: end` closes the conversation.
- Reaching a node or finishing the conversation counts for `dialogue` objectives.
- A node can also play a recorded line with `voice: <media id>` (see
  [narrative runtime](2.0/narrative-runtime.md)).

Binding a conversation to an NPC, and every NPC option, is in
[content format: conversations](content-format.md#conversations).

---

## Parties

Party features use **MysticRPG's party system** when MysticRPG is installed and provides one. `/mq
integrations` shows whether a party provider is connected.

| Feature | How |
|---|---|
| Shared objective progress | `shared: true` on the objective. |
| Run events for the whole party | the `party` event with nested `events`. |
| Party conditions | `inParty`, `partySize`. |
| Party stories (2.0) | puzzles and story content with `audience: party` or `auto`. |

v1 party features need only the list of party members. **2.0 party stories also need a stable
party ID** from the party system, so the party keeps one shared story while members come and go. Until
MysticRPG's party system supplies one, 2.0 party stories run per player instead (`/mq integrations`
reports this as partial). Developers: see `QuestPartyProvider#partyId` and
`MysticQuestsApi#partyMemberLeft` / `#partyDisbanded`.

---

## 2.0 story content

Everything above is "v1" content, and it keeps working unchanged. 2.0 adds story features in the
same package folders, usually in a file named `narrative.yml`:

| Section | Adds |
|---|---|
| `tagSchemas`, `variableSchemas` | **Typed** tags and variables: declared once with a type, scope and default, and checked at reload. |
| `puzzles` | Puzzles with per-player or per-party random selection (for example 4 of 10 hidden keys). |
| `overlays` | Doors, seals and bridges that are open for some players and closed for others. |
| `speakers`, `media` | Voice lines, subtitles, sound effects and music per player. |
| `cutscenes` | Timed scenes with a safe skip. |

### v1 tags and 2.0 tags are separate

| | v1 (this guide) | 2.0 (`narrative.yml`) |
|---|---|---|
| Name | bare: `met_farmer` | namespaced: `avalon:met_farmer` |
| Declared | no, just use it | yes, in `tagSchemas` / `variableSchemas` |
| Values | text | typed (integer, boolean, enum, ...) |
| Scopes | player, global, entity, block, volume, quest | player, quest, quest_session, party, world, server, temporary |
| Typos | create a new tag silently | refused at reload |

They are stored separately. A v1 quest or conversation runs 2.0 script with the `narrative` event
and checks 2.0 state with the `narrative` condition; 2.0 content runs v1 events and conditions
directly, and v1 quests count 2.0 progress with `signal` objectives. The tutorial for all of this,
including a story split across nested chapter packages, is [writing 2.0 stories](2.0/story-scripting.md).
New stories can use either; use 2.0 when you want typing, puzzles or per-player story isolation.
The full 2.0 reference, with a worked example, is in [narrative runtime](2.0/narrative-runtime.md)
and [examples/packages/druid_temple](../examples/packages/druid_temple).

---

## A complete example

A small package split across folders:

```text
packages/
  greenvale/
    package.yml
    npcs/
      farmer.yml
    quests/
      wolves.yml
    shared.yml
```

`package.yml`

```yaml
version: 1.0.0
```

`shared.yml`: reusable pieces

```yaml
conditions:
  met_farmer: { type: tag, tag: greenvale_met_farmer }

events:
  cheer: { type: sendMessage, message: "&aGreenvale cheers for %player%!" }
```

`npcs/farmer.yml`: the quest giver

```yaml
conversations:
  farmer:
    speaker: Farmer Bram
    entity: { name: Farmer Bram }
    start: [done, report, offer]           # most specific first
    nodes:
      - id: done
        conditions: [ { type: questCompleted, quest: wolves } ]
        text: "Thanks again for the help."
      - id: report
        conditions: [ { type: questActive, quest: wolves } ]
        text: "How goes the hunt?"
        choices:
          - text: "The wolves won't trouble you again."
            next: end
      - id: offer
        text: "Wolves have been taking my sheep. Can you help?"
        events: [ { type: addTag, tag: greenvale_met_farmer } ]
        choices:
          - text: "I'll deal with them."
            events: [ { type: startQuest, quest: wolves } ]
            next: end
          - text: "Not now."
            next: end
```

`quests/wolves.yml`: the quest

```yaml
quests:
  wolves:
    displayName: Wolf Trouble
    description: Thin out the wolves near Farmer Bram's fields, then tell him.
    startConditions: [met_farmer]
    objectives:
      - { id: hunt, displayName: Defeat wolves, type: kill, target: Wolf_Black, amount: 5, shared: true }
      - { id: report, displayName: Tell Farmer Bram, type: dialogue, target: greenvale:farmer:report }
    startEvents:
      - { type: incrementVariable, scope: quest, key: attempts }
    rewards:
      - { type: giveItem, item: hytale:gold_ingot, amount: 3 }
      - { type: ref, ref: cheer }
```

Stored IDs: `greenvale:wolves`, `greenvale:farmer`, `greenvale:met_farmer`, `greenvale:cheer`.
More complete examples: `examples/packages/` (`tutorial`, `beton_style_adventure`,
`showcase_tutorial`, `druid_temple`).

---

## Testing a quest

| Step | Command |
|---|---|
| Load your changes | `/mquest reload` |
| Start it on yourself | `/mquest start self greenvale:wolves` |
| See your progress | `/mquest progress` or `/journal` |
| Complete it instantly | `/mquest complete self greenvale:wolves` |
| Set one objective | `/mquest narrative objective <you> greenvale:wolves hunt set 4` |
| Look at your tags | `/mquest state tag player self list` |
| Everything about a player | `/mq debug <player>` |
| Retake an abandoned quest | `/mquest reaccept self greenvale:wolves` |
| Reset a quest completely | Quest Studio, **Player State** tab |

Staff commands need the permissions listed in the [server guide](server-guide.md#permissions).

---

## Common mistakes

| Symptom | Likely cause |
|---|---|
| Reload refused with "unknown type" | A typo in `type`, or a type from a mod that is not installed. |
| Reload refused with a duplicate ID | The same ID twice in one package. |
| A `kill` objective never counts | The `target` is not the exact role name (check with `/npc role`), or the kill was not the player's own blow. |
| A quest never appears on the board | Its `startConditions` fail for you, or it was abandoned (see `reacceptConditions`). |
| Players lost progress after an edit | The quest's ID or package changed; progress is stored against the old ID. |
| A child package's quests are missing | The subfolder has no `package.yml`, so its files belong to the parent package (and their IDs too). |
| A cross-package reference cannot be found | The package folder name contains a dash, or the `>` shorthand was written with `:`. |
| Two packages interfere through a tag | They share a bare tag name. Prefix it or use `packageScoped: true`. |
| An NPC opens the wrong greeting | `start` candidates are tried in order; put the most specific first and the unconditional one last. |
