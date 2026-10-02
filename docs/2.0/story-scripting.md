# Writing 2.0 Stories

How to script MysticQuests 2.0 by hand: what 2.0 adds on top of ordinary quests, how the two talk to
each other, and how to lay out a multi-chapter story across nested folders. Every example here is
taken from [examples/packages/sealed_grove](../../examples/packages/sealed_grove), which the test
suite loads on every build, so the syntax is known to work.

Read [creating quests by hand](../creating-quests.md) first: folders, packages, IDs, objectives,
tags and variables all work as described there. The full 2.0 reference (every action, rule and
option) is [narrative runtime](narrative-runtime.md).

**Contents:** [What 2.0 adds](#what-20-adds) · [How v1 and 2.0 connect](#how-v1-and-20-connect) ·
[Stories and sessions](#stories-and-sessions) · [Typed state](#typed-state) ·
[Actions and conditions](#actions-and-conditions) ·
[The narrative event and condition](#the-narrative-event-and-condition) ·
[Walkthrough: The Sealed Grove](#walkthrough-the-sealed-grove) ·
[Nested stories](#nested-stories) · [Testing and debugging](#testing-and-debugging) ·
[Rules of thumb](#rules-of-thumb)

---

## What 2.0 adds

A **v1 quest** is the part the player sees in the journal: a name, objectives, rewards. 2.0 does not
replace it. It adds a **story layer** underneath:

| 2.0 feature | What it gives you |
|---|---|
| Typed tags and variables | Declared once with a type and scope; typos fail the reload instead of creating new state. |
| Stories and sessions | One copy of a story's state per player or per party. |
| Puzzles | Inputs from trigger volumes, ten rule types, random per-audience selection. |
| Story NPCs | NPCs only the owning player or party can see, hit or talk to. |
| Overlays | Doors, seals and bridges that are open for some players and closed for others. |
| Media | Voice lines, subtitles, stingers and music per player. |
| Cutscenes | Timed scenes that always finish cleanly, even when skipped. |

All of it lives in the same package folders as your quests, usually in a file named
`narrative.yml` (the name does not matter; the section keys do).

---

## How v1 and 2.0 connect

| Direction | How |
|---|---|
| A quest or conversation runs 2.0 script | The `narrative` **event**: `{ type: narrative, story: ..., actions: [...] }` |
| A quest or conversation checks 2.0 state | The `narrative` **condition**: `{ type: narrative, condition: {...} }` |
| 2.0 script advances a quest | `mysticquests:signal` counts toward a v1 `signal` objective |
| 2.0 script runs a v1 event | Write the v1 event as-is in a 2.0 action list: `{ type: startQuest, quest: ... }`, `{ type: actionBar, ... }` |
| 2.0 script checks v1 state | Write the v1 condition as-is: `requires: { type: questActive, quest: ... }` |
| The world triggers 2.0 | Trigger volumes: puzzle inputs, and the `mysticquests:cutscene_play` / `trigger_state` effects |

A typical story uses all of them: the quest gives the journal entry, a trigger volume feeds a
puzzle, the puzzle's outputs change story state and send a signal, and the signal completes the
quest's objective.

---

## Stories and sessions

A **story** is a name, such as `grove:sealed_grove`. Each player or party playing it gets a
**session**: their own copy of the story's state.

```yaml
puzzles:
  - id: grove:runes
    story: grove:sealed_grove     # which story this belongs to
    audience: auto                # whose session: player, party, or auto (party when in one)
```

A session holds:

- tags and variables with `scope: quest_session`,
- puzzle progress and selections,
- trigger-volume and overlay overrides with `scope: session`,
- the running cutscene and checkpoints,
- a ledger of which steps already ran, so rewards are never given twice.

A session is created the first time something needs it: a puzzle input, a cutscene, or a
`narrative` event that names the story. Two groups in the same place each have their own session,
so their puzzles, doors, NPCs and music never interfere.

**Parties.** With `audience: party` or `auto`, a party shares one session. That needs the party
system to supply a stable party ID (MysticRPG's party system, through `QuestPartyProvider#partyId`);
until it does, party stories fall back to one session per player. `/mq integrations` shows which.

---

## Typed state

2.0 tags and variables are declared before use:

```yaml
tagSchemas:
  - id: grove:seal_broken
    scope: quest_session            # one per story session
    description: This audience broke the grove seal.

variableSchemas:
  - id: grove:runes_lit
    type: integer
    scope: quest_session
    default: 0
  - id: grove:warden_trust
    type: enum
    values: [wary, friendly]
    scope: player                   # follows the player everywhere
    default: wary
```

- IDs are `namespace:path`, lower case. Pick one namespace for your server or story (`grove:`).
- Types: `boolean`, `integer`, `long`, `double`, `string`, `enum` (with `values`), `duration`,
  `timestamp`, `location`, `entity`, and `list` / `set` / `map` of those.
- Scopes you will use most: `quest_session` (this story run), `player` (this player, any story),
  `party`, `world` and `server`.
- Schemas are global across all packages, so declare shared ones once (see
  [Nested stories](#nested-stories)).

These are **separate** from v1 tags and variables (`addTag`, `setVariable`), which stay untyped.
Use 2.0 state for story logic; use v1 state for simple quest flags.

---

## Actions and conditions

2.0 action lists appear in puzzle `outputs` / `onInput`, cutscene `steps` / `onEnd`, and the
`narrative` event. The common actions:

```yaml
- { type: mysticquests:tag.add, tag: grove:seal_broken }
- { type: mysticquests:variable.increment, variable: grove:runes_lit }
- { type: mysticquests:variable.set, variable: grove:warden_trust, value: friendly }
- { type: mysticquests:signal, signal: grove:seal_broken }              # completes v1 signal objectives
- { type: mysticquests:overlay.hide, overlay: grove:seal }              # opens a door for this audience
- { type: mysticquests:cutscene.play, cutscene: grove:seal_opening }
- { type: mysticquests:media.play, media: grove:seal_breaks }
- { type: mysticquests:music.set, music: grove:grove_theme }
- { type: mysticquests:checkpoint, label: before_guardian }            # staff can rewind here
- { type: startQuest, quest: the_grove }                                # any v1 event works too
```

2.0 conditions:

```yaml
{ type: tag, tag: grove:seal_broken }
{ type: variable, variable: grove:runes_lit, op: ">=", value: 2 }
{ all: [ {...}, {...} ] }          # also any, none, not, at_least / at_most / exactly with count
{ type: questActive, quest: the_grove }                                # any v1 condition works too
```

The full list, with every parameter, is in
[narrative runtime: actions](narrative-runtime.md#24-actions-and-transitions).

**When actions run.** Inside a puzzle or cutscene, each step runs **once per transition**: if a
step fails (say, the player went offline), it is retried later from that step, and nothing before
it repeats. Inside a v1 `narrative` event, the actions run **every time the event fires**, like any
other v1 event.

---

## The narrative event and condition

These are how a v1 quest or conversation reaches into 2.0. They can go anywhere v1 events and
conditions go: `startEvents`, `completeEvents`, `rewards`, conversation nodes and choices,
`startConditions`, `reacceptConditions`, named events and conditions, and inside `if`, `folder`,
`party`, `and`, `or` and `not`.

### The event

```yaml
startEvents:
  - type: narrative
    story: grove:sealed_grove         # optional: run in this story's session (opened if needed)
    audience: auto                    # optional: player, party or auto (default)
    actions:
      - { type: mysticquests:music.set, music: grove:grove_theme }
```

Without `story`, the actions run for the player alone, which is right for `player`-scoped state.
With `story`, `quest_session` state and session-scoped overrides apply to that story's session.

### The condition

```yaml
conditions:
  - type: narrative
    story: grove:sealed_grove         # optional: read this story's session
    condition: { type: tag, tag: grove:seal_broken }
```

A condition never creates a session. If the player has no session for the story yet, session
state reads as unset, so the condition above is simply false.

Both are checked at `/mquest reload`: an unknown action, undeclared variable or wrong type fails the
reload with the file and location, like any other content error.

---

## Walkthrough: The Sealed Grove

The example package, step by step. The player meets a warden, lights three runes, the seal opens
for them alone, and chapter 2 unlocks.

```text
packages/sealed_grove/
  package.yml
  shared/narrative.yml          speakers, media, schemas for the whole story
  chapter_1/
    package.yml                 child package "sealed_grove/chapter_1"
    narrative.yml               the seal, the cutscene, the rune puzzle
    quests.yml                  the v1 quest "the_grove"
    conversations.yml           the warden
  chapter_2/
    package.yml                 child package "sealed_grove/chapter_2"
    quests.yml                  "deeper_roots", unlocked by chapter 1
```

**1. The warden starts the quest** (`chapter_1/conversations.yml`). The first `start` node whose
conditions pass opens, so the warden thanks you once the seal is broken, using a 2.0 condition:

```yaml
start: [thanks, waiting, hello]
nodes:
  - id: thanks
    conditions:
      - type: narrative
        story: grove:sealed_grove
        condition: { type: tag, tag: grove:seal_broken }
    text: "The grove breathes again. Thank you."
  - id: hello
    text: "Few find this grove. Will you help me wake it?"
    choices:
      - text: "I will."
        events: [ { type: startQuest, quest: the_grove } ]
        next: end
```

**2. The quest sets the mood** (`chapter_1/quests.yml`). Its objectives are ordinary v1 ones; the
second waits for a 2.0 signal. Starting the quest switches on story music for this player's
session only:

```yaml
objectives:
  - { id: meet, displayName: Speak with the Grove Warden, type: dialogue, target: "sealed_grove/chapter_1:warden" }
  - { id: seal, displayName: Break the grove seal, type: signal, signal: "grove:seal_broken" }
startEvents:
  - type: narrative
    story: grove:sealed_grove
    actions:
      - { type: mysticquests:music.set, music: grove:grove_theme }
```

**3. The runes are a puzzle** (`chapter_1/narrative.yml`). Each rune is a trigger volume. The puzzle
only accepts inputs while the v1 quest is active, and its outputs run once:

```yaml
puzzles:
  - id: grove:runes
    story: grove:sealed_grove
    audience: auto
    requires: { type: questActive, quest: the_grove }
    inputs:
      - { id: north, volume: "avalon:grove_rune_north" }
      - { id: east, volume: "avalon:grove_rune_east" }
      - { id: south, volume: "avalon:grove_rune_south" }
    rule: all
    onInput:
      - { type: mysticquests:variable.increment, variable: grove:runes_lit }
      - { type: actionBar, message: "&aA rune flares to life." }
    outputs:
      - { type: mysticquests:tag.add, tag: grove:seal_broken }
      - { type: mysticquests:cutscene.play, cutscene: grove:seal_opening }
      - { type: mysticquests:signal, signal: grove:seal_broken }
```

**4. The seal opens for them alone.** The seal is an overlay: a placed entity with `HardCollision`
that everyone bumps into until it is hidden for an audience. The cutscene hides it, plays a
stinger, and always restores the camera:

```yaml
overlays:
  - id: grove:seal
    entity: "uuid:00000000-0000-4000-8000-000000000a11"   # your seal entity's UUID
    default: present

cutscenes:
  - id: grove:seal_opening
    story: grove:sealed_grove
    steps:
      - { at: 0, type: setCamera, mode: third, locked: true }
      - { at: 0.5, type: mysticquests:media.play, media: grove:seal_breaks, cosmetic: true }
      - { at: 1, type: mysticquests:overlay.hide, overlay: grove:seal }
    onEnd:
      - { type: setCamera, mode: first }
```

**5. The signal completes the quest**, whose `completeEvents` stop the music and record the
warden's trust in a `player`-scoped variable that outlives the story:

```yaml
completeEvents:
  - type: narrative
    story: grove:sealed_grove
    actions:
      - { type: mysticquests:music.clear }
      - { type: mysticquests:variable.set, variable: grove:warden_trust, value: friendly }
```

**6. Chapter 2 checks both kinds of state** (`chapter_2/quests.yml`):

```yaml
startConditions:
  - { type: questCompleted, quest: "sealed_grove-chapter_1>the_grove" }
  - type: narrative
    condition: { type: variable, variable: grove:warden_trust, op: "==", value: friendly }
```

---

## Nested stories

There is no separate "nested quest" object in 2.0: nesting comes from **folders and packages**
(how files are organised and named) and **story keys** (which state is shared). They are independent,
so choose each on purpose.

### Folders: one child package per chapter

Give each chapter or act its own child package (a subfolder with a `package.yml`), and keep shared
2.0 content at the top:

```text
packages/
  avalon_season1/
    package.yml
    shared/
      narrative.yml             schemas, speakers, music used by every chapter
      conversations.yml         NPCs who appear across chapters
    chapter_1/
      package.yml               "avalon_season1/chapter_1"
      quests.yml
      narrative.yml
      act_1/                    plain subfolders: still chapter_1
        quests.yml
      act_2/
        quests.yml
    chapter_2/
      package.yml               "avalon_season1/chapter_2"
      ...
```

- Quest IDs are per package (`avalon_season1/chapter_1:the_grove`), so chapters can reuse local names.
  Refer across chapters with `avalon_season1-chapter_1>the_grove`.
- 2.0 IDs (`grove:seal_broken`) are global and do not depend on folders. Declare schemas once, in
  the shared folder; every package can use them.
- Use child packages for things you want to version or switch off on their own
  (`enabled: false` in that chapter's `package.yml`). Use plain subfolders for tidiness.

### Story keys: shared or fresh state

| Choice | Effect | Use when |
|---|---|---|
| **One story key for the season** (`grove:season1`) | Chapters share one session: puzzle progress, `quest_session` tags and overrides carry across chapters. | The chapters are one continuous adventure for the same group. |
| **One story key per chapter** (`grove:season1.chapter_1`) | Each chapter starts with fresh session state. | Chapters are replayable or can be played with different parties. |

Carry results from one chapter to the next with `player`-scoped (or `party`-scoped) variables, as
the grove does with `grove:warden_trust`, or with `questCompleted` on the previous chapter's quest.

### Chaining chapters

```yaml
# chapter_2/quests.yml
quests:
  deeper_roots:
    startConditions:
      - { type: questCompleted, quest: "avalon_season1-chapter_1>the_grove" }
```

To start the next chapter automatically, end the previous quest with
`{ type: startQuest, quest: "avalon_season1-chapter_2>deeper_roots" }` in its `completeEvents`, or let
the next chapter's NPC offer it.

> The spec's season / chapter / act / node graph (a visual quest graph) belongs to the future web
> editor. Today the structure is expressed with packages, quests and story keys as above.

---

## Testing and debugging

| Task | Command |
|---|---|
| Load and validate everything | `/mquest reload` (errors list the file and location) |
| Start the quest | `/mquest start self sealed_grove/chapter_1:the_grove` |
| See a player's whole story state | `/mq debug <player>` |
| Puzzle state, and which inputs were dealt | `/mquest narrative puzzle <player> grove:runes` |
| Teleport to an input | `/mquest narrative goto <player> grove:runes 1` |
| Replay or skip a scene | `/mquest narrative cutscene <player> grove:seal_opening play` / `cutscene <player> skip` |
| Start the story over | `/mquest narrative story <player> grove:sealed_grove restart` |
| Check content against 2.0 | `/mquest narrative migrate` |

---

## Rules of thumb

- **Keep the journal in v1, the world in 2.0.** Quests and objectives are what players read; puzzles,
  doors, story NPCs and scenes are 2.0.
- **Finish a puzzle with a signal**, and give the quest a `signal` objective for it. That keeps
  the quest's progress honest even if the player reconnects mid-puzzle.
- **State changes are never `cosmetic`** in a cutscene, and a scene that moves the camera puts it
  back in `onEnd`. The reload enforces both.
- **Use `quest_session` scope for anything a party shares**, and `player` scope for what follows a
  player between stories.
- **Don't rename story keys or 2.0 IDs** once players have progress; sessions and state are stored
  against them.
