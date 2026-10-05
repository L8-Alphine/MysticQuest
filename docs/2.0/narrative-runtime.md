# Narrative Runtime (MysticQuests 2.0, Phases 1–3 and 7)

The narrative runtime decides which version of the story a player or party is experiencing. It sits
beside the v1 quest runtime rather than replacing it: v1 quests, conversations and scripts keep
working unchanged, and narrative content can call into them through a compatibility bridge.

This first increment provides:

- **Typed state.** Namespaced tags with expiry and provenance. Typed variables in ten scopes.
  Schemas that turn typos into reload errors.
- **Condition trees.** `all`, `any`, `none`, `not`, `xor`, `at_least`, `at_most`, `exactly`, with
  static checks for impossible and contradictory trees.
- **Typed actions.** Each returns `SUCCESS`, `SKIPPED`, `RETRYABLE_FAILURE` or `TERMINAL_FAILURE`.
  Actions run as idempotent transitions, so rewards are never paid twice.
- **Story sessions.** Player- or party-owned, versioned, persisted, checkpointed, restored on join,
  and forked or detached when a member leaves.
- **Logical trigger activation.** Enable or disable a trigger volume per session, player, party or
  globally, without touching the engine's all-or-nothing switch.
- **The puzzle engine.** Ten rule types and deterministic, persisted random selection. Outputs
  fire exactly once per round.

The engine findings behind these choices are in [gap-analysis.md](gap-analysis.md).

---

## 1. Configuration

`mods/MysticQuests/config.json` gains a `narrative` section. A server whose config predates it uses
these defaults:

```json
"narrative": {
  "serverId": "default",
  "dataPath": "data/narrative",
  "flushIntervalMillis": 1000,
  "partyExitPolicy": "fork",
  "openNamespaces": [],
  "subtitles": "chat",
  "fallbackLocale": "en-US"
}
```

| Key | Meaning |
|---|---|
| `serverId` | This server's stable id. It owns `server`-scope state and global trigger overrides, and is stamped on every session it touches. **Give every server on a network its own id, and never change it** once players have state. |
| `dataPath` | Where narrative documents live, relative to the data directory. Point several servers at one shared directory to carry sessions across transfers. |
| `flushIntervalMillis` | How often pending changes are written. Quit and shutdown always write immediately. |
| `partyExitPolicy` | `fork`: a member who leaves keeps a solo copy of the party's active stories. `detach`: the story stays with the party. |
| `openNamespaces` | Namespaces where undeclared tags and variables are accepted, besides `legacy`. For migration only: in an open namespace a typo creates a new variable instead of failing the reload. |
| `subtitles` | Where subtitles appear: `chat`, `title` (the event title) or `off`. |
| `fallbackLocale` | The language every localised voice line must have a recording for. Players whose language has no recording hear this one, and the fallback is logged once per line and language. |

---

## 2. Content

Narrative content lives in the same package folders as quests, in three sections: `variableSchemas`,
`tagSchemas` and `puzzles`. They can sit in any package file (`narrative.yml` is conventional), or in
array files named after the section (`puzzles.json`, `variableSchemas.json`, `tagSchemas.json`).

Every package's schemas compile before any package's puzzles, so a puzzle may use a variable
declared in another package. **Any error fails the whole reload**, and the server keeps running the
previous quests and puzzles together. The one exception is the very first load at start-up: there,
narrative errors are logged as `SEVERE` and the narrative runtime starts empty, because another
mod's narrative types may not be registered yet. Fix the content, then run `/mquest reload`.

A complete worked example is [examples/packages/druid_temple](../../examples/packages/druid_temple).
For a tutorial, including running 2.0 script from v1 quests (the `narrative` event and condition)
and laying out a story across nested chapter packages, see [writing 2.0 stories](story-scripting.md).

### 2.1 Identifiers

Tags, variables, puzzles, signals and action types use `namespace:path`, all lower case. The path may
group with single `.`, `/` or `-`:

```
hyzion:avalon.discovered    hyzion:druid_temple.keys_found    mysticquests:tag.add
```

An identifier without a namespace is an error that says how to fix it. v1 tags were bare strings,
which is how two packages used to collide on `completed`.

### 2.2 Schemas

```yaml
tagSchemas:
  - id: hyzion:druid_temple.keys_complete
    scope: quest_session
    ttl: 1h                    # optional: expires an hour after being added
    description: This audience has recovered all of its keys.
  - id: hyzion:druid_temple.awakened
    scope: player
    milestone: Awakened the druid temple   # optional: shown on the player's web portal (MysticIdentity)

variableSchemas:
  - id: hyzion:druid_temple.keys_found
    type: integer
    scope: quest_session
    default: 0
  - id: hyzion:temple.phase
    type: enum
    values: [dormant, awake, sealed]
    scope: world
```

**Types:** `boolean`, `integer`, `long`, `double`, `string`, `uuid`, `duration` (`15s`, `500ms`,
`2m`, `1h`, `1d` or ISO `PT15S`), `timestamp` (ISO instant), `location` (`{world,x,y,z}` or
`world:x:y:z`), `entity` (`uuid:<id>`, `generation:<id>`, or a bare UUID), `list<T>`, `set<T>`,
`map<T>`, `enum` (with `values`).

Writes are checked against the type. `"12"` is accepted as an integer, because v1 stored everything
as text. `4.5` is refused, because truncating it would change the story. So are `"yes"` for a boolean
and overflowing increments.

**Scopes:**

| Scope | Owner | Persisted |
|---|---|---|
| `player` | one player, across quests | yes |
| `quest` | one player within one quest | yes |
| `quest_session` | one story session, player- or party-owned; travels with the session | yes, inside the session |
| `party` | one party, by the provider's stable id | yes |
| `world` | one world on this server | yes |
| `server` | this server (`serverId`) | yes |
| `temporary` | one player while online | no |
| `account` | the person: the MysticIdentity identity the player's Hytale account is linked to, shared by every account they link | yes; with MysticIdentity installed, resolves for linked players (others skip writes, retryably); without it, a reload error |
| `network` | needs a shared state provider | unsupported until configured |
| `season` | needs a season provider | unsupported until configured |

An unsupported scope is a reload error. Party scope on a server with no party provider is a warning,
because it cannot resolve for anyone. A party-scoped *write* for a player who is not in a party is
refused as retryable. It is not redirected to the player.

### 2.3 Conditions

```json
{ "all": [
    { "type": "tag", "tag": "hyzion:druid_temple.entered" },
    { "type": "variable", "variable": "hyzion:druid_temple.keys_found", "op": ">=", "value": 4 },
    { "not": { "type": "tag", "tag": "hyzion:druid_temple.completed" } }
] }
```

| Form | Meaning |
|---|---|
| `all` / `any` / `none` (`conditions`) | every / at least one / no child passes |
| `not` (`condition`) | negation |
| `xor` (exactly two `conditions`) | exactly one of the two. N-ary xor is ambiguous, so use `exactly` with `count: 1`. |
| `at_least` / `at_most` / `exactly` (`count`, `conditions`) | counting |
| `tag` (`tag`, optional `scope`, `invert`) | the tag is live |
| `variable` (`variable`, `op`, `value`, optional `scope`) | `==` `!=` `>` `>=` `<` `<=` `contains` `exists` |
| `namespace:type` | a registered condition type |
| a v1 type without namespace (`questActive`, `permission`, …) | evaluated by the v1 quest service |
| `mysticquests:legacy.condition` with `condition: {…}` | the explicit v1 form; needed for v1 `tag`/`variable`, whose short names mean the narrative leaves |

Both the keyed form above and the typed form (`{"type": "all", "conditions": [...]}`) are accepted.
The compiler reports unknown ids, wrong scopes and mistyped operands. It also reports trees that can
never pass (`at_least 3` of two children, `>= 5` together with `< 3`, a condition beside its own
negation) and trees that always pass.

### 2.4 Actions and transitions

| Type | Parameters |
|---|---|
| `mysticquests:tag.add` / `tag.remove` / `tag.toggle` | `tag`, `scope?`, `ttl?` (add only) |
| `mysticquests:variable.set` / `variable.increment` / `variable.remove` | `variable`, `scope?`, `value` / `amount` (default 1) |
| `mysticquests:trigger.enable` / `trigger.disable` / `trigger.clear` | `volume` or `volumes`, `scope?` (`session` inside a session, else `player`; never global unless written) |
| `mysticquests:puzzle.input` | `puzzle`, `input`, `activate?` |
| `mysticquests:puzzle.reset` | `puzzle`, `reroll?` |
| `mysticquests:signal` | `signal`, `amount?`; counted by v1 objectives of type `signal` |
| `mysticquests:entity.spawn` | `definition` (MysticGeneration), `variable?` (entity-typed), `x`/`y`/`z` or `distance?`, `yaw?`, `onDeath?` (actions); the NPC is claimed for the session as it spawns |
| `mysticquests:entity.claim` / `entity.release` | `variable` (entity-typed) or `entity` (`uuid:<id>` / `generation:<id>`), `onDeath?` (actions, claim only); claim needs a session |

**Who gets the results.** A story entity's `onDeath` actions run once, when it dies, in the session that owns it, with the owning audience as the actor: the killer when they belong to it, otherwise the owning player. The claim then ends. Give a story boss's rewards this way rather than through its drop list: dropped items have no owner in Hytale, so anyone nearby can pick them up.

```yaml
- type: mysticquests:entity.spawn
  definition: grove_guardian
  onDeath:
    - { type: mysticquests:tag.add, tag: grove:guardian_slain }
    - { type: giveItem, item: Grove_Relic, amount: 1 }     # a v1 event, through the bridge
```
| `mysticquests:entity.despawn` | as above; MysticGeneration NPCs only |

| `mysticquests:overlay.show` / `overlay.hide` / `overlay.reset` | `overlay`, `scope?` (as trigger actions) |
| `mysticquests:media.play` | `media`, `audience?` (`player`, `party`, `session`, `world`, `global`; default session, else player), `at?` {x,y,z} or `entity?`/`variable?`, `subtitles?` (default true) |
| `mysticquests:media.stop` | `channel?` (default `voice`, `all` for every channel), `audience?` |
| `mysticquests:music.set` / `music.clear` | `music` (a `kind: music` id, or `none` for the world's own music), `scope?` (as trigger actions) |
| `mysticquests:cutscene.play` | `cutscene`; plays in the running session when it is the scene's story, otherwise opens the scene's own |
| `mysticquests:cutscene.skip` | `force?` (also skips scenes marked `skippable: false`) |
| `mysticquests:checkpoint` | `label`; snapshots the running session so staff can rewind to it. Permanent effects are never repeated after a rewind |

**Overlays** (`overlays:` section) are per-audience world presentation:

```yaml
overlays:
  - id: hyzion:druid_temple.seal       # an entity with HardCollision, placed with the Hitbox tool
    entity: "uuid:9a3f1c2e-0000-4000-8000-0000000005ea"
    default: present                   # blocks everyone until overlay.hide opens it for an audience
  - id: hyzion:druid_temple.bridge
    entity: "generation:..."           # MysticGeneration identity also works
    default: absent                    # nobody can use it until overlay.show reveals it
```

A player who is not shown an overlay entity does not collide with it either, because player
collision is client-side on this engine. That makes overlays per-player walls, doors and bridges
that never rubber-band. See the
[Phase 8 notes](gap-analysis.md#phase-8-notes-walls-per-player-verified-against-the-engine).

**Media** (`speakers:` and `media:` sections) are story audio addressed by id (§13, §15):

```yaml
speakers:
  - id: hyzion:old_man
    name: Old Man                      # or nameKey: a translation key
    type: npc                          # npc, narrator, system, player, unknown, custom
media:
  - id: hyzion:old_man.warning_001
    kind: voice                        # voice, sfx, ambient, music, stinger, ui, cinematic
    speaker: hyzion:old_man
    sounds:                            # SoundEvent ids per language; or one "sound" for every language
      en-US: SFX_OldMan_Warning_001_EN
      fr-FR: SFX_OldMan_Warning_001_FR
    subtitleKey: dialogue.old_man.warning_001   # follows the reader's language; or "subtitle": literal text
    duration: 4.2                      # seconds; queued lines need it
    spatial: entity                    # 2d (default), position, entity
    interruption: replace_same_speaker # voice defaults to queue on the "voice" channel
  - id: hyzion:temple.tension
    kind: music
    music: Music_Temple_Tension        # a MusicContainer id
```

Every sound goes to single listeners, so two players in one room can hear different lines. Voice
lines on one channel queue per listener. The engine cannot stop a sound already playing, so
`interrupt` decides what starts next and an interrupted line plays out underneath. Music chosen with
`music.set` is stored with its scope and followed per listener (session, player, party, then
global), so it comes back after a reconnect. A v1 dialogue node can name a voice line with
`"voice": "hyzion:old_man.warning_001"`; it plays to the reader when the node opens. See the
[Phase 9 notes](gap-analysis.md#phase-9-notes-dialogue-and-media).

**Cutscenes** (`cutscenes:` section) are timelines of ordinary actions (§17):

```yaml
cutscenes:
  - id: hyzion:druid_temple.awakening
    story: hyzion:druid_temple         # the session it plays in; audience as for puzzles
    skippable: true                    # default; false lets only staff skip it
    steps:
      - { at: 0,   type: setCamera, mode: third, locked: true }
      - { at: 0.5, type: mysticquests:media.play, media: hyzion:narrator.intro, cosmetic: true }
      - { at: 6,   type: mysticquests:tag.add, tag: hyzion:druid_temple.awakened }
    onEnd:                             # runs however the scene ends
      - { type: setCamera, mode: first }
```

Skipping (`/mquest skip`), staff skipping, starting another scene in the session, and coming back
after a disconnect or restart all finish the scene the same way: remaining required steps run at
once, `cosmetic` steps are dropped, queued story audio stops, and `onEnd` runs. The loader refuses a
cosmetic state change and a camera change without a `setCamera` in `onEnd`. A trigger volume starts
a scene with the `mysticquests:cutscene_play` effect. See the
[Phase 10 notes](gap-analysis.md#phase-10-notes-cutscenes).

A **claimed** entity belongs to the session's audience (the player, or whoever is in the party now).
Nobody else can see it, target it, be targeted by it, damage it, be damaged by it, or interact with
it. Two players in the same room can each fight their own copy of the boss.
| any v1 event without namespace (`giveItem`, `sendTitle`, `startQuest`, …) | run by the v1 quest service |

Action lists run as **transitions**. Each settled step (success or skip) is recorded in the session's
ledger under `<transition>#<step>` and never runs again. A failure stops the list, and a later retry
resumes at the failed step. Give a step a stable `stepId` when a release might reorder its list.

Steps whose effect lives outside the session (every v1 event, and any handler that declares itself
external) are recorded as **permanent**. A checkpoint rollback replays session state but never
repeats a permanent step, so an item or payment is never given twice. Override per step with
`"permanent": true|false`.

A v1 event that needs the player online (an item, a title, a command) returns *retryable* while they
are offline. The next join retries it.

### 2.5 Puzzles

```yaml
puzzles:
  - id: hyzion:druid_temple.hidden_keys
    story: hyzion:druid_temple       # the session it lives in; defaults to the puzzle id
    audience: auto                    # player | party | auto (party when in one)
    requires: { type: questActive, quest: hidden_keys }   # optional gate on every input
    inputs:
      - { id: key_1, volume: "avalon:druid_key_1" }       # fired by the volume's ENTER
      - { id: plate_a, volume: "avalon:plate_a", toggleable: true }   # EXIT releases it
      - { id: rune_n, group: north, weight: 2 }
    selection: { active: 4 }          # optional; choose 4 of the inputs (or of "candidates")
    rule: all                         # or { type: ..., ... }
    repeatable: false                 # true: a new round starts after each solve
    onInput:   [ ... ]                # after each accepted input
    onMistake: [ ... ]                # sequence rules only
    onReset:   [ ... ]
    outputs:   [ ... ]                # once per round
```

| Rule | Parameters | Completes when |
|---|---|---|
| `all` | — | every active input is activated |
| `any` | — | one is |
| `n_of_m` | `required` | `required` distinct active inputs are |
| `sequence` | `sequence`, `resetOnMistake` (true) | the inputs arrive in order |
| `unordered_sequence` | `sequence` | every listed input is activated, in any order |
| `exact` | `required` | exactly `required` are active at once (use toggleable inputs) |
| `timed` | `window`, `required` (all) | `required` fall inside a sliding window |
| `weighted` | `threshold` | activated weights reach the threshold |
| `groups` | `perGroup` (1) | every group has `perGroup` activated |
| `state_machine` | `initial`, `states` | a `terminal` state is reached; states may have `onEnter` |

Puzzles that can never be solved are reload errors. Examples: an `n_of_m` needing more inputs than
are active, an unreachable weighted threshold, a state machine with no reachable terminal state, a
sequence naming a missing input, or random selection combined with a rule that names its inputs.
Invalid outputs are reported as `INVALID_PUZZLE_OUTPUT`.

**Selection** is made the first time an audience touches the puzzle, and it is stored. The choice is
seeded from the puzzle id, session id and round, so it is reproducible for debugging, but the stored
copy is what counts. A content change cannot move a player's keys. Unselected inputs do nothing for
that audience. Only `reset` with `reroll` draws again.

### 2.6 Trigger volumes

A puzzle input with a `volume` is fed by that volume's events automatically. Five volume types are
also available in the trigger-volume editor:

| Type | Kind | Use |
|---|---|---|
| `mysticquests:trigger_enabled` | condition | passes when the volume (or `Volume`) is logically enabled for the triggering player; add it to any volume MysticQuests should be able to switch per audience |
| `mysticquests:puzzle_input_available` | condition | passes while an input is selected for the player's audience and not yet activated; makes a key's effects visible only to those who need it |
| `mysticquests:puzzle_input` | effect | feeds an input (or releases it with `Release`) |
| `mysticquests:puzzle_reset` | effect | starts a new round, optionally with `Reroll` |
| `mysticquests:trigger_state` | effect | enables, disables or clears an override for the player's sessions, the player, their party, or everyone |

The engine can only switch a volume on or off for everyone. Per-audience activation is a MysticQuests
layer, resolved from most to least specific: **session → player → party → global → enabled**. If two of
a player's sessions disagree, disabled wins. A volume that is logically disabled for a player feeds
neither puzzles nor `triggerEnter`/`triggerExit` objectives for them, while it keeps working for
everyone else in the same place.

### 2.7 Sessions

A session is one player's or one party's run of one story (`story` key). It holds that audience's
`quest_session` state, trigger overrides, puzzle progress, transition ledger and checkpoints. It never
holds live engine handles: everything visible is rebuilt from it on join, restart or transfer.

- **Join** restores the player's own sessions and their party's, then retries outputs that could not
  finish while they were away.
- **Quit** writes and unloads the player's sessions, and the party's when no member is left online.
- **Leaving a party** under `fork` gives the leaver a solo copy of each active party story. The copy
  includes the ledger, so rewards they earned in the party are not paid again. No copy is made if
  they already have their own session for that story. **Disband** forks every member and archives
  the party's sessions, so a reused party id starts fresh.
- Party sessions need a party provider that supplies a stable id: MysticRPG's party system through
  `QuestPartyProvider#partyId`, or MysticGuilds. Without one, `auto` audiences fall back to each
  player's own session.

---

## 3. Operations

### Commands

| Command | Permission |
|---|---|
| `/mq debug <player> [export]` (§21: v1 quests, sessions, tags, variables, trigger overrides, puzzles, story entities, overlays, media and cutscene in one view; `export` writes it to `debug/` as JSON) | `mysticquests.command.admin.debug` |
| `/mquest narrative sessions <player>` | `mysticquests.command.admin.debug` |
| `/mquest narrative media <player>` (what each channel is playing and queuing, and which level chose their music) | `mysticquests.command.admin.debug` |
| `/mquest narrative cutscene <player>` (the scene they are watching) | `mysticquests.command.admin.debug` |
| `/mquest narrative cutscene <player> <cutscene> play` / `cutscene <player> skip` (skips even unskippable scenes) | `mysticquests.command.admin.narrative` |
| `/mquest skip` (players: skip the scene you are watching, if it allows it) | `mysticquests.command.journal` |
| `/mquest narrative checkpoint <player>` (list) | `mysticquests.command.admin.debug` |
| `/mquest narrative checkpoint <player> rewind <label> [reason…]` (audited) | `mysticquests.command.admin.narrative` |
| `/mquest narrative media <player> replay <media> [reason…]` (audited) | `mysticquests.command.admin.narrative` |
| `/mquest narrative migrate [export]` (§25: v1 entries that run unchanged, v1 patterns with a safer 2.0 replacement, unreadable files, quarantined state; `export` writes it to `reports/`) | `mysticquests.command.admin.debug` |
| `/mquest narrative goto <world:volume>` / `goto <player> <puzzle> [n]` (teleport yourself to a volume content names, or to the n-th input in that player's puzzle selection) | `mysticquests.command.admin.narrative` |
| `/mquest narrative story <player> <story> restart [reason…]` (audited; abandons the active session, which stays on disk, so the story starts fresh) | `mysticquests.command.admin.narrative` |
| `/mquest narrative objective <player> <quest> <objective> complete\|reset\|set <n> [reason…]` (audited; v1 objectives, completing the last one pays the quest rewards) | `mysticquests.command.admin.narrative` |
| `/mquest narrative puzzle <player> <puzzle>` | `mysticquests.command.admin.debug` |
| `/mquest narrative puzzle <player> <puzzle> reset\|reroll [reason…]` | `mysticquests.command.admin.narrative` |
| `/mquest narrative trigger <player> <world:volume>` | `mysticquests.command.admin.debug` |
| `/mquest narrative trigger <player> <world:volume> enable\|disable\|clear [session\|player\|party\|global] [reason…]` | `mysticquests.command.admin.narrative` |
| `/mquest narrative validate` | `mysticquests.command.admin.debug` |
| `/mquest narrative stats` (§28, §29: sizes against limits, counters, slowest operations) | `mysticquests.command.admin.debug` |
| `/mquest narrative stats reset` | `mysticquests.command.admin.narrative` |

`mysticquests.admin` grants all of them. Every change is written to the audit trail (`audit`
collection): actor, target, session, server, before, after and reason. It is also logged.

### Limits and metrics

`NarrativeMetrics` (on `NarrativeRuntime#metrics()`) counts transitions completed and failed, failed
actions, faulting custom conditions, missing action and condition types, validation errors, trigger
events, puzzle inputs, mistakes and resets, sessions restored from storage and quarantined,
recovered cutscenes, slow operations and reached limits. It times every action by type
(`action:<type>`), condition evaluation (`condition`, and `condition:<type>` for custom ones),
trigger events (`trigger.event`), content compiles and state flushes. Anything over 5 ms is
counted as slow and logged with its content path.

`NarrativeLimits` (config `maxStorySessions`, `maxStoryEntities`, `maxPuzzleInputs`) fails safe:

| Limit | When reached |
|---|---|
| Story sessions | Logged once and counted. Sessions still open, because refusing one strands a player. |
| Story entities | `entity.claim` and `entity.spawn` fail retryably, so the transition runs again once other stories release theirs. A claimed entity can always move between sessions. |
| Puzzle inputs | A reload warning (`LIMIT_EXCEEDED`); the puzzle still loads. |

Evaluation is event-driven: a trigger event looks up only the puzzle bindings of its own volume, and
a puzzle checks its `requires` only for an input it accepts. `NarrativeLoadTest` pins this down with
200 puzzles and 500 players (one requirement check per event, nothing for an unbound volume) and
restores 1,000 sessions after a restart.

### Storage

```
mods/MysticQuests/data/narrative/
  state/          one document per owner (player, quest, party, world, server)
  sessions/       one document per session
  session-index/  each owner's session ids
  audit/          one document per intervention, never rewritten
```

Every document carries `schemaVersion`. A document from a **newer** release, or one that cannot be
read, is *quarantined*. That owner runs on empty state, is reported, and is never written back, so a
mixed-version network mid-rollout cannot destroy data. Filenames are percent-encoded with a fixed
prefix, so no id can escape its folder or hit a reserved name.

---

## 4. Extending

### A condition or action type

```java
MysticQuestsApi api = MysticQuestsApi.get();
api.narrativeConditions().register(NamespacedId.parse("mymod:has_skill"), new ConditionHandler() {
    public boolean test(ScopeContext context, ObjectNode parameters) {
        return skills.level(context.actor(), parameters.path("skill").asText()) >= parameters.path("min").asInt(1);
    }
    public void validate(ObjectNode parameters, String path, DiagnosticReport report) {
        if (!parameters.hasNonNull("skill")) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "needs \"skill\"");
        }
    }
});

api.narrativeActions().register(NamespacedId.parse("mymod:grant_skill"), new ActionHandler() {
    public ActionResult execute(ActionContext context, ObjectNode parameters) {
        if (!online(context.scope().actor())) {
            return ActionResult.retryable("player offline");      // retried on their next join
        }
        skills.grant(context.scope().actor(), parameters.path("skill").asText());
        return ActionResult.success();
    }
    public boolean external(ObjectNode parameters) {
        return true;    // lives outside the session: never replay it after a rollback
    }
});
```

- Register before content loads, ideally in your plugin's `start()` with MysticQuests as a
  dependency. If you register later, run `/mquest reload`.
- Report honestly. `SUCCESS` is recorded and never retried. Use `RETRYABLE_FAILURE` for "not now"
  and `TERMINAL_FAILURE` for "never".
- Handlers run on the world thread mid-tick. Keep them cheap.

### A puzzle rule

1. Add the constant to `PuzzleRuleType` with its parse aliases.
2. Add its parameters to `PuzzleDefinition.Rule` if it needs new ones, and read them in
   `PuzzleCompiler#rule`.
3. Add a solvability check to `PuzzleCompiler#checkSolvable`. A rule that can be authored
   unsolvable must be refused at load.
4. Add its completion test to `PuzzleRules#complete`. If inputs need special handling (ordering,
   expiry), add a branch to `QuestPuzzleService#apply`.
5. Add a case to `PuzzleRulesTest`.

### A trigger-volume effect or condition

1. Extend `TriggerEffect` or `TriggerCondition`, with a codec in `MysticTriggerCodecs` chained from
   `BASE_CODEC`.
2. Register it in `MysticTriggerVolumeRegistrar#registerTypes`. That runs in `setup()`; a type
   registered later is dropped from volumes decoded before it.
3. Route it through `NarrativeTriggerBridge`. The runtime may not be bound yet, and the bridge
   declines safely.
4. Add the editor labels to `Server.Languages.en-US/server.lang`.
5. Run `validate_hytale_code_refs` from the Hytale Workshop MCP over the new files.

### A subsystem's per-session state

Implement `SessionComponent` and store it with `session.component(key, Type.class, Type::fromJson,
Type::new)`. Call `session.markChanged()` after mutating it, while holding the session's monitor.
Components are saved, transferred, forked and checkpointed with the session. Ones this build does not
understand are preserved verbatim. Dialogue, media and cutscene progress will use this.

### Scopes that need a provider

`ScopeSupport` decides which scopes resolve. A bridge that provides account, season or network
identity should mark the scope supported and supply the id through `ScopeContext`. Until then,
content using it fails validation instead of silently degrading.

### Tests

`NarrativeTestKit` (test sources) builds the whole runtime over an in-memory store, with a hand-moved
clock and a party map. `restart()` builds a second runtime over the same store, which is how restart
and reconnect behaviour is tested.
