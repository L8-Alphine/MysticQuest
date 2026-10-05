# Trigger Volumes and Quests

This guide is for world builders and quest authors who place trigger volumes in the world and want
them to work with MysticQuests: counting a visit for a quest, reacting only for players who have
earned something, switching a volume per player or per party, and stopping players from using
blocks or entities inside an area unless they meet a requirement.

Trigger volumes come from Hytale's built-in `Hytale:TriggerVolumes` plugin, which MysticQuests
requires. You place and edit them with the in-game trigger volume editor. MysticQuests adds its own
conditions and effects to that editor and listens to what volumes do.

Related pages: [creating quests](creating-quests.md) for objectives, tags and variables,
[content format](content-format.md) for the field reference, and
[narrative runtime](2.0/narrative-runtime.md#26-trigger-volumes) for puzzles and story sessions.

**Contents:** [The short version](#the-short-version) ·
[How volumes and quests connect](#how-volumes-and-quests-connect) ·
[Anatomy of a volume](#anatomy-of-a-volume) · [MysticQuests conditions](#mysticquests-conditions) ·
[MysticQuests effects](#mysticquests-effects) · [Rules](#rules-stopping-things-inside-a-volume) ·
[Recipes](#recipes) · [Combining conditions](#combining-conditions) ·
[Not supported yet](#not-supported-yet) · [Testing](#testing-and-debugging) ·
[Common mistakes](#common-mistakes)

---

## The short version

- A volume has **conditions**, **effects**, **rejection effects** and **rules**. Conditions decide,
  per player, whether the effects or the rejection effects run. Rules (No Use, No Build, …) are
  always on and apply to **everyone** in the area.
- Walking into a volume can progress a `triggerEnter` objective, but only if the volume's ENTER
  conditions pass for that player. Rejected players get the rejection effects and no credit.
- MysticQuests conditions (`mysticquests:has_tag`, `mysticquests:variable`) let a volume react only
  for players with a quest tag or variable. Quests and stories set that state; the volume reads it.
- To switch a volume on or off for **one** player, party or story run, give it the
  `mysticquests:trigger_enabled` condition and switch it with `mysticquests:trigger.enable` /
  `trigger.disable` (from a story) or the `mysticquests:trigger_state` effect (from a volume).
- To stop players using things inside an area, add a **No Use** rule. It blocks everyone. To let
  only some players get a result, block the click and **gate the outcome**: the rule's deny signal
  runs your conditions for the player who clicked, and your effects give them the result.

---

## How volumes and quests connect

### The three links

| Direction | How | Use it for |
|---|---|---|
| Volume → quest | The engine tells MysticQuests when a player enters or leaves. That progresses `triggerEnter` / `triggerExit` objectives and feeds puzzle inputs bound to the volume. | "Find the ruins", "leave the arena", key hunts |
| Volume ↔ state | MysticQuests conditions and effects inside the volume read and write quest tags and variables, and run quest events. | "Only for players who have the key", "remember that they visited" |
| Quest or story → volume | Logical activation: a story (or a volume, or staff) enables or disables the volume for a session, player, party or everyone. The volume obeys through its `mysticquests:trigger_enabled` condition. | One-shot volumes, areas that open once a quest step is done |

### What happens when a player walks in

1. The engine checks the volume is enabled, the entity matches its `TargetTypes`, and the volume is
   not on `Cooldown` for them.
2. The engine tests every condition with `Event: ENTER` for that player. **All** must pass. If they
   do, the effects with `Event: ENTER` run. If any fails, the rejection effects with `Event: ENTER`
   run instead.
3. **Only if the conditions passed**, the engine reports the entry to plugins. MysticQuests then
   checks logical activation for that player (session → player → party → global). If the volume is
   logically disabled for them, nothing more happens.
4. Otherwise MysticQuests feeds the puzzle inputs bound to this volume and progresses matching
   `triggerEnter` objectives.
5. While the player stays inside, `Event: TICK` effects run, but only for players whose ENTER passed.
6. When they leave, the engine reports the exit to plugins **whether or not ENTER passed**. Steps 3
   and 4 repeat for `triggerExit` and for toggleable puzzle inputs (a pressure plate is released).
   Effects with `Event: EXIT` run, gated by any `Event: EXIT` conditions.

What follows from this:

- **ENTER conditions gate quest credit too.** A player turned away at the door does not complete
  `triggerEnter`. This is usually what you want.
- **EXIT is not gated by the volume's ENTER conditions.** A `triggerExit` objective counts for a
  player who was rejected on the way in. If exit credit must be earned, gate the volume with logical
  activation (`mysticquests:trigger_enabled`), which MysticQuests checks on EXIT as well, rather
  than with tag conditions.
- **Only ENTER and EXIT reach objectives and puzzle bindings.** Other events (`BLOCK_USED`,
  `SIGNAL_RECEIVED`, `TICK`, …) reach MysticQuests only through effects you place on the volume,
  such as `mysticquests:puzzle_input` or `mysticquests:add_tag`.

### Volume ids and volume keys

| Where | What to write | Example |
|---|---|---|
| `triggerEnter` / `triggerExit` objective `target` (or `volume`) | The volume's **id**, exactly as the editor shows it. No world prefix. | `ruins_gate` |
| Puzzle input `volume`, `mysticquests:trigger.*` actions, the `Volume` field of `trigger_enabled` / `trigger_state`, `/mquest narrative trigger`, `/mquest narrative goto` | The volume **key**: `world:volumeId` | `avalon:ruins_gate` |
| Volume-scoped tags and variables with no `Target` | Filled in for you with the volume key | |

Write the world name in lower case. The engine lower-cases it when a world loads its volumes.

---

## Anatomy of a volume

You normally build volumes in the editor, but it helps to know what the editor writes. These are the
fields that matter for quest work:

| Field | Meaning |
|---|---|
| `Conditions` | Tests run per entity, per event. |
| `Effects` | Run when the conditions for that event pass. |
| `RejectionEffects` | Run when a condition for that event fails. Your "you can't do that yet" feedback goes here. |
| `Rules` | Always-on restrictions (No Use, No Build, …). They ignore conditions. See [Rules](#rules-stopping-things-inside-a-volume). |
| `RulesActive` | `false` suspends every rule on the volume without deleting them. |
| `Enabled` | The engine's on/off switch, for **everyone**. MysticQuests never flips it; it uses its own per-audience layer. |
| `TargetTypes` | Which kinds of entity the volume tracks (players, NPCs, …). |
| `Cooldown` | Seconds before the same entity can activate the volume again. |
| `ActivationDelay`, `ConditionTiming` | Wait before firing, and whether conditions are tested before or after the wait. |
| `Tags` | Key/value labels on the volume. Engine effects and conditions use them to find other volumes (`MatchKey` / `MatchValue`). Not the same as MysticQuests tags. |
| `EffectAsset` | Points the volume at a shared effect asset. On world load the volume's conditions, effects, rejection effects and rules are **replaced** by the asset's, so edit the asset, not the volume. |

Every condition and effect also has:

| Field | Meaning |
|---|---|
| `Type` | `mysticquests:has_tag`, `PermissionCondition`, `SendMessage`, … |
| `Event` | Which event it belongs to. **Required.** A condition only gates effects of the same event. |
| `Entry` | Optional branch number (default 0). Conditions only gate effects with the same `Entry`. |
| `Interval`, `Delay` | Effects only: repeat interval while inside (for `TICK`) and a delay before running. |

### Events

| Event | Fires when |
|---|---|
| `ENTER` / `EXIT` | An entity enters or leaves. |
| `TICK` | Every tick while inside (after a passed ENTER). |
| `BLOCK_USED` | A block inside the volume **has been** used. It fires after the use, so it cannot undo it. |
| `BLOCK_PLACED` / `BLOCK_BROKEN` | A block inside the volume was placed or broken. |
| `SIGNAL_RECEIVED` | The volume received a signal: from another volume's `SendSignal`, an interaction, an NPC action, or a rule that denied something. |
| `TAG_ADDED` / `TAG_REMOVED`, `ENTITY_DIED`, `VOLUME_CREATE` | Volume tag changes, a death inside, the volume being created. |

### How conditions are tested

- All conditions for the current event and `Entry` must pass (AND). There is no OR; see
  [Combining conditions](#combining-conditions).
- A condition that throws an error counts as failed, so broken content never lets a player through.
- If a volume has `Event: TICK` conditions, a player already standing inside activates it the
  moment those conditions start passing, as if they had just entered. While they keep failing, the
  rejection effects fire once per stay, not every tick. Use this when someone may earn the
  requirement while inside, for example by talking to an NPC in the room.
- Each `Entry` number is a separate branch, and **each accepted branch reports its own ENTER** to
  MysticQuests. A volume with two entries that both pass can count a `triggerEnter` objective twice
  in one visit. Keep objective volumes to a single entry.

---

## MysticQuests conditions

| Type | Fields | Passes when |
|---|---|---|
| `mysticquests:has_tag` | `Scope`, `Target`, `Tag`, `Invert` | the owner has the tag |
| `mysticquests:variable` | `Scope`, `Target`, `Key`, `Value`, `Operator`, `Invert` | the variable compares true. `Operator`: `eq` (default), `ne`, `gt`, `gte`, `lt`, `lte`, `exists`. `gt`…`lte` compare whole numbers. |
| `mysticquests:trigger_enabled` | `Volume`, `Invert` | the volume is logically enabled for the triggering player. Leave `Volume` blank for this volume, or name another `world:volumeId` so one controller gates several. |
| `mysticquests:puzzle_input_available` | `Puzzle`, `Input`, `Invert` | the puzzle input was dealt to the player's audience and is not yet used. See the [druid temple example](../examples/packages/druid_temple/narrative.yml). |

`Invert: true` flips the result, which gives you "does **not** have the tag".

### Scope and Target

`Scope` says whose state is read; `Target` overrides who that is. Leave `Target` empty in almost every
case.

| `Scope` | Owner when `Target` is empty |
|---|---|
| `player` (default) | the player who triggered the volume |
| `global` | the whole server |
| `entity` | the entity that triggered the volume |
| `block` | the block the event happened at (`world:x:y:z`). Only block events and signals carry a position; on ENTER, EXIT and TICK there is no block and the condition fails. |
| `volume` | this volume (`world:volumeId`) |

### Things to know

- These conditions read the **same tags and variables your quests use** (`addTag`, `setVariable`,
  `tag`, `variable` in quest files). A tag a quest adds with `addTag` is visible to
  `mysticquests:has_tag` straight away.
- They do **not** read 2.0 story state (namespaced tags such as `hyzion:temple.opened` in
  `quest_session` or `party` scope). To gate a volume on story progress, switch the volume from the
  story with `mysticquests:trigger.enable` / `trigger.disable` instead. See
  [recipe 4](#4-switch-a-volume-from-quest-or-story-progress).
- A quest tag written with `packageScoped: true` is stored as `package.tag`. Write the full name in
  the volume: `Tag: "avalon.wolves_done"`.
- Until MysticQuests has finished starting, `has_tag`, `variable` and `puzzle_input_available` fail
  and `trigger_enabled` passes, so volumes behave as if MysticQuests were absent.

---

## MysticQuests effects

Effects run for the entity that triggered the volume. The story effects (puzzles, cutscenes, trigger
state) only act for players.

### State

| Type | Fields | Does |
|---|---|---|
| `mysticquests:add_tag` / `remove_tag` | `Scope`, `Target`, `Tag` | adds or removes a tag |
| `mysticquests:set_variable` | `Scope`, `Target`, `Key`, `Value` | sets a variable |
| `mysticquests:increment_variable` | `Scope`, `Target`, `Key`, `Amount` (default 1, may be negative) | adds to a number |
| `mysticquests:remove_variable` | `Scope`, `Target`, `Key` | clears a variable |

### Quest events, messages and commands

| Type | Fields | Does |
|---|---|---|
| `mysticquests:action` | `Action` plus any of `Scope`, `Target`, `Viewer`, `Tag`, `Key`, `Value`, `Amount`, `Mode`, `Message`, `Subtitle`, `Locked`, `Package` | runs one quest event by type name, through the same code quests use |
| `mysticquests:rich_message` | `Message`, `Audience` (`player` or `global`), `Package` | sends chat with placeholders and `&` / `&#RRGGBB` colours |
| `mysticquests:run_command` | `Command`, `ExecuteAs` (`player` or `console`), `Package` | runs a command. As `player`, the player's own permissions apply. |
| `mysticquests:event` | | older form of `mysticquests:action`, kept so existing worlds load. Use `mysticquests:action` in new volumes. |

`mysticquests:action` copies only the fields listed above onto the event, and `Message` fills both
`message` and `title`. Event types that need nothing else work:

| `Action` | Fields it uses |
|---|---|
| `sendMessage`, `actionBar` | `Message` |
| `sendTitle` | `Message` (the title), `Subtitle` |
| `hidePlayer` / `showPlayer`, `preventTargeting` / `allowTargeting` | `Target`, `Viewer` |
| `runCommand` | `Value` (the command) |
| events other mods register | whatever they read from these fields |

Event types that need other fields (`startQuest` needs `quest`, `giveItem` needs `item`, `if`,
`ref`) cannot be run this way. Set a tag in the volume and let the quest or conversation react to it,
or use `mysticquests:run_command`.

### Stories

| Type | Fields | Does |
|---|---|---|
| `mysticquests:trigger_state` | `Volume` (blank = this one), `State` (`enable`, `disable` (default), `clear`), `Scope` (`session` (default), `player`, `party`, `global`) | switches a volume for the triggering player's audience. `session` applies to every active story the player is in. |
| `mysticquests:puzzle_input` | `Puzzle`, `Input`, `Release` | feeds a puzzle input, or releases a toggleable one |
| `mysticquests:puzzle_reset` | `Puzzle`, `Reroll` | starts a new round, optionally dealing new inputs |
| `mysticquests:cutscene_play` | `Cutscene` | plays a story cutscene for the player's audience |

---

## Rules: stopping things inside a volume

Rules are the engine's way to forbid actions in an area. They are always on while the volume is
enabled, `RulesActive` is true and the rule's own `Enabled` is not `false`.

| Rule | Stops |
|---|---|
| `NoUse` | using (pressing use on) blocks and entities, except `ExceptBlocks` / `ExceptBlockTags` |
| `NoDoorOpen` | opening doors |
| `NoBuild` | placing blocks, except `ExceptBlocks` |
| `NoDestroy` | breaking blocks, except `ExceptBlocks` / `ExceptTools` |
| `NoHarvest` | harvesting crops and gathering pickups |
| `NoDamage` | damage to entities inside (optionally only some entities, or only PvP) |
| `NoHeal` | healing |
| `NoTick` | block ticking (growth, spreading) |

The other rules change behaviour rather than forbid it: `DamageMultiplier` scales damage, `Fly` lets
players fly while inside, and `CreativePlacement` places blocks without using them up.

How they behave:

- **They apply to everyone.** Rules do not look at conditions, so `mysticquests:has_tag` on the
  volume does not exempt anyone from a rule. Logical activation does not switch rules off either.
- **What counts is where the target is.** `NoUse` cancels the use when the block or entity being used
  is inside the volume, wherever the player stands.
- **Overlapping volumes:** the most restrictive rule wins. An action is allowed only if every
  matching rule excepts it.
- **Deny signals.** The block rules (`NoUse`, `NoDoorOpen`, `NoBuild`, `NoDestroy`, `NoHarvest`)
  send a `SIGNAL_RECEIVED` event when they cancel something. It goes to the rule's own volume by
  default (`Target: "Self"`; `"Other"` and `"Both"` with `MatchKey` / `MatchValue` / `Radius` reach
  tagged volumes nearby). The event carries the player who tried, the block, and the rule's
  `SignalKeys` / `SignalValues`. This is how you run MysticQuests logic for a blocked click.
- Rules are switched with the engine's `ModifyRules` effect, which changes the volume for everyone
  and is saved with the world.

---

## Recipes

The JSON shows the lists the editor writes. Ids in angle brackets are placeholders for your own.

### 1. Count a visit as a quest objective

Place a volume with id `ruins_gate` in world `avalon`. It needs no conditions or effects.

```yaml
quests:
  sunken_ruins:
    displayName: The Sunken Ruins
    objectives:
      - id: find_ruins
        displayName: Find the ruins
        type: triggerEnter
        target: ruins_gate          # the volume id, without the world
```

Objectives only progress for players who have the quest active, so the volume does nothing for
anyone else.

### 2. Only let a volume work for players who have a tag

The ruins seal opens only for players who carry the `ruins_key` tag. Everyone else is told why.

```json
{
  "Conditions": [
    { "Type": "mysticquests:has_tag", "Event": "ENTER", "Scope": "player", "Tag": "ruins_key" }
  ],
  "Effects": [
    { "Type": "mysticquests:action", "Event": "ENTER", "Action": "sendTitle",
      "Message": "The Sunken Ruins", "Subtitle": "The seal recognises you" }
  ],
  "RejectionEffects": [
    { "Type": "mysticquests:rich_message", "Event": "ENTER",
      "Message": "&7A cold force holds you back. &oYou need the ruins key." }
  ]
}
```

Give the tag from a quest or conversation:

```yaml
completeEvents:
  - { type: addTag, tag: ruins_key }
```

Because the condition gates ENTER, a `triggerEnter` objective on this volume also only counts for
key holders. Add more conditions to the list to require more (all must pass), for example a
`mysticquests:variable` with `Key: "ruins_stage"`, `Operator: "gte"`, `Value: "2"`.

To react only **while a quest is active**, add a tag in the quest's `startEvents` and remove it in
`completeEvents`, then check that tag. Quests have no abandon hook, so an abandoned quest leaves the
tag behind; prefer tags that mean "has earned X" over "is doing quest Y".

### 3. Fire once per player (or per party, or per story run)

A lore volume that should greet each player once:

```json
{
  "Conditions": [
    { "Type": "mysticquests:trigger_enabled", "Event": "ENTER" }
  ],
  "Effects": [
    { "Type": "mysticquests:rich_message", "Event": "ENTER",
      "Message": "&6The statues watch you pass." },
    { "Type": "mysticquests:trigger_state", "Event": "ENTER", "State": "disable", "Scope": "player" }
  ]
}
```

The first visit passes, shows the message and disables the volume **for that player**. It keeps
working for everyone else. Use `"Scope": "party"` for once per party, or `"session"` for once per
story run (it resets when the story restarts). Player and party overrides are saved with the player
or party; reset one with `/mquest narrative trigger <player> avalon:statue_hall clear player`.

### 4. Switch a volume from quest or story progress

Give the volume the `mysticquests:trigger_enabled` condition (as in recipe 3), then switch it:

- **From a 2.0 story**, in any action list (`outputs`, `onInput`, a transition):

  ```yaml
  - type: mysticquests:trigger.enable
    volume: "avalon:temple_passage"
    scope: session                 # session (default inside a story), player, party or global
  ```

  `trigger.disable` and `trigger.clear` work the same way, and `volumes:` takes a list. A global
  change must be written out; it is never the default.
- **From a v1 quest**, set a tag and gate the volume on it ([recipe 2](#2-only-let-a-volume-work-for-players-who-have-a-tag)).
- **By hand**, `/mquest narrative trigger <player> avalon:temple_passage enable|disable|clear [session|player|party|global]`.

Resolution goes from most to least specific: **session → player → party → global → enabled**. If two
of a player's stories disagree, disabled wins. To start a volume closed for everyone and open it per
player, disable it once globally, then enable it in a narrower scope:

```
/mquest narrative trigger self avalon:temple_passage disable global
```

A session or player `enable` beats the global `disable`. The
[druid temple example](../examples/packages/druid_temple/narrative.yml) does exactly this.

### 5. Cancel interactions inside a volume

Stop everyone from using chests, levers, doors and NPCs in the vault, except the exit lever:

```json
{
  "Rules": [
    { "Type": "NoUse", "ExceptBlocks": ["<exit lever block id>"] }
  ]
}
```

`ExceptBlocks` matches a block and all its states (an open door matches the closed door's id).
`ExceptBlockTags` excepts whole families by block tag. Add `NoDoorOpen`, `NoBuild` or `NoDestroy`
rules beside it to lock the area down further.

### 6. Tell the player why they were blocked

Rules cancel silently. Use the deny signal to run MysticQuests effects for the player who clicked:

```json
{
  "Rules": [
    { "Type": "NoUse" }
  ],
  "Effects": [
    { "Type": "mysticquests:action", "Event": "SIGNAL_RECEIVED", "Action": "actionBar",
      "Message": "&cThe vault is sealed." }
  ]
}
```

If other things also signal this volume (another volume's `SendSignal`, an NPC action), label the
rule's signal and check the label, so the message only answers blocked clicks:

```json
{
  "Rules": [
    { "Type": "NoUse", "SignalKeys": ["denied"], "SignalValues": ["use"] }
  ],
  "Conditions": [
    { "Type": "TagCondition", "Event": "SIGNAL_RECEIVED", "Source": "Event",
      "TagKey": "denied", "TagValue": "use" }
  ]
}
```

A failed `TagCondition` here runs the volume's `SIGNAL_RECEIVED` rejection effects, so keep
rejection effects off this event, or put the deny handling in its own `Entry`.

### 7. Allow an interaction only when a condition holds

Rules cannot exempt individual players, so a "only attuned players may use the altar" altar works the
other way round: **block the click for everyone, and give the result only to players who qualify.**
The deny signal runs the volume's conditions for the player who clicked, so the outcome is decided
per player even though the rule is not.

```json
{
  "Rules": [
    { "Type": "NoUse" }
  ],
  "Conditions": [
    { "Type": "mysticquests:has_tag", "Event": "SIGNAL_RECEIVED", "Scope": "player", "Tag": "altar_attuned" }
  ],
  "Effects": [
    { "Type": "mysticquests:puzzle_input", "Event": "SIGNAL_RECEIVED",
      "Puzzle": "<namespace:puzzle>", "Input": "altar" },
    { "Type": "mysticquests:action", "Event": "SIGNAL_RECEIVED", "Action": "sendTitle",
      "Message": "The altar answers" }
  ],
  "RejectionEffects": [
    { "Type": "mysticquests:rich_message", "Event": "SIGNAL_RECEIVED",
      "Message": "&7The altar stays cold. &oPerhaps the druids can attune you." }
  ]
}
```

What the "result" can be:

- **Per player:** quest state (`mysticquests:add_tag`, `set_variable`), puzzle inputs, cutscenes,
  titles and messages, `mysticquests:run_command`, and engine effects that act on the triggering
  entity (`Teleport`, `GiveItem`, `EntityEffect`).
- **For everyone:** anything that changes the world, such as `ControlDoors`, `PlaceBlock` or
  `ModifyRules`. An opened door is open for everyone until it closes.

Other conditions work the same way: `PermissionCondition` (`Permission`), `ItemCondition` (`Item`,
`Location`: `InHand`, `Hotbar`, `Inventory` or `Carried`, `Quantity`, `Consume`), `GameModeCondition`,
`TimeOfDay`, `mysticquests:variable`, `mysticquests:trigger_enabled`. With `ItemCondition` and
`"Consume": true`, the item is taken only when every condition passed.

If the native use itself must go through for qualifying players (they really open the chest), that
is not possible with the built-in rules today; see [Not supported yet](#not-supported-yet). When it
is fine for **everyone** to use the block, skip the rule and react on `BLOCK_USED` instead. It fires
after the use, gated by your conditions, so only qualifying players get the outcome.

### 8. Unlock a no-use zone for everyone

When something happens in the world, switch the vault's rules off with the engine's `ModifyRules`
effect. Without `MatchKey` it changes the volume it is on; with `MatchKey` / `MatchValue` it changes
tagged volumes within `Radius`:

```json
{ "Type": "ModifyRules", "Event": "ENTITY_DIED", "Operation": "SetRulesActive", "Active": false,
  "MatchKey": "vault", "Radius": 64 }
```

Add `"Rule": { "Type": "NoUse" }` to switch only that rule type. The change is saved with the world
and applies to every player. MysticQuests has no quest event that edits rules, so drive this from a
volume event.

### 9. Only while a status effect is active

No trigger condition can read a player's active entity effects (buffs, potions, curses). Two ways
around it:

- **Mirror the effect into a tag** where you apply it. A shrine that blesses players inside the
  temple:

  ```json
  {
    "Effects": [
      { "Type": "EntityEffect", "Event": "ENTER", "Effect": "<effect id>", "Mode": "Apply" },
      { "Type": "mysticquests:add_tag", "Event": "ENTER", "Tag": "temple_blessed" },
      { "Type": "EntityEffect", "Event": "EXIT", "Effect": "<effect id>", "Mode": "Remove" },
      { "Type": "mysticquests:remove_tag", "Event": "EXIT", "Tag": "temple_blessed" }
    ]
  }
  ```

  Other volumes then check `mysticquests:has_tag` `temple_blessed`. Apply the effect without a
  `Duration`: a timed effect wears off on its own, but the tag would stay.
- **Check the item that gives the effect** with `ItemCondition`, if the effect comes from holding or
  carrying something (a lantern, a relic).

If the effect comes from somewhere MysticQuests never sees (a potion the player drank), neither works
reliably yet.

### 10. Show a volume's effects only to players who need them

For key hunts where each player is dealt different keys, put `mysticquests:puzzle_input_available`
on each key volume. Its particles and sounds then play only for players whose selection includes that
key, and stop once they have it. See the
[druid temple example](../examples/packages/druid_temple/narrative.yml) and
[puzzles](2.0/narrative-runtime.md#25-puzzles).

### 11. Feed a puzzle on a click instead of a step

A puzzle input bound with `volume:` only hears ENTER and EXIT. For a lever or a deny signal, leave
`volume` off the input and add the effect to the volume:

```json
{ "Type": "mysticquests:puzzle_input", "Event": "BLOCK_USED", "Puzzle": "<namespace:puzzle>", "Input": "lever_2" }
```

---

## Combining conditions

| You want | Do |
|---|---|
| A **and** B | List both conditions with the same `Event`. |
| **Not** A | `Invert: true` on MysticQuests conditions; `Inverted: true` on the engine's `TagCondition`. |
| A **or** B | There is no OR. Work it out in quest logic and store the answer: an `if` event with an `or` condition that adds one tag, which the volume then checks. Or use two `Entry` branches with mutually exclusive conditions (`A`, and `B` with `not A`) so only one branch can run. |
| Different outcomes for different players | Effects for the passing case, rejection effects for the rest. For more than two outcomes, use `Entry` branches. |

---

## Not supported yet

| Gap | Workaround |
|---|---|
| Rules that exempt individual players ("only attuned players may open the chest") | Block for everyone and gate the outcome ([recipe 7](#7-allow-an-interaction-only-when-a-condition-holds)). Exempting the native action per player needs a MysticQuests rule type with its own enforcement, which does not exist yet. |
| A condition on active entity effects | Mirror into a tag, or check the item ([recipe 9](#9-only-while-a-status-effect-is-active)). |
| Volume conditions reading 2.0 story state | Switch the volume from the story with `mysticquests:trigger.*` ([recipe 4](#4-switch-a-volume-from-quest-or-story-progress)). |
| Cancelling an action from an effect | Effects run after the fact. Only rules cancel. |
| `startQuest`, `giveItem`, `if`, `ref` through `mysticquests:action` | Set a tag and react in quest content, use `mysticquests:run_command`, or the engine's `GiveItem` effect. |

---

## Testing and debugging

| To | Use |
|---|---|
| See a player's tags, variables, trigger overrides and puzzles | `/mq debug <player>` |
| Check or set a player tag | `/mquest state tag player self has <tag>` (also `add`, `remove`, `list`) |
| Check a volume's own state | `/mquest volume tag <world:volumeId> list`, `/mquest volume state <world:volumeId> get [key]` |
| Switch a volume for a player | `/mquest narrative trigger <player> <world:volumeId> enable\|disable\|clear [scope]` |
| Go to a volume | `/mquest narrative goto <world:volumeId>` |

If MysticQuests types are missing from the editor, check the server log at startup for
"Trigger volume types could not be registered". The types must register before any world loads, or
volumes that use them lose those entries when the world is saved. Do not save worlds while that
warning is showing.

---

## Common mistakes

| Symptom | Cause |
|---|---|
| `triggerEnter` never counts | The objective `target` has the world prefix (`avalon:ruins_gate`); use the bare id. Or the volume's ENTER conditions fail for that player. Or the volume is logically disabled for them. |
| `triggerEnter` counts twice per visit | The volume has two `Entry` branches that both passed. |
| `triggerExit` counts for players who were turned away | EXIT is not gated by ENTER conditions; use logical activation. |
| A condition never passes for a quest tag | The quest used `packageScoped: true`; write `package.tag` in the volume. Or the tag is a 2.0 story tag, which volumes cannot read. |
| A puzzle input bound to a volume ignores clicks | Bindings only hear ENTER and EXIT; use a `mysticquests:puzzle_input` effect with `Event: BLOCK_USED`. |
| Players who meet the condition are still blocked | Rules ignore conditions. Gate the outcome instead. |
| The "blocked" message also appears for other signals | Label the rule's signal and check it with a `TagCondition` (`Source: "Event"`). |
| Block-scoped state set by a volume is not seen by a quest interaction | Volumes key blocks by world name, quest interactions by world UUID. Use player or volume scope to share state between them. |
| Edits to a volume's effects are lost on restart | The volume uses an `EffectAsset`; edit the asset. |
