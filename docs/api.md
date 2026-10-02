# MysticQuests API

MysticQuests exposes a stable Java surface for other mods, plus extension points that let a content
pack use a third-party feature without any Java of its own.

Everything below lives in `org.hyzionstudios.mysticquests.api`. Internal services are deliberately not
part of the surface, so they can be reworked without breaking dependants.

## Getting the API

```java
if (MysticQuestsApi.isAvailable()) {
    MysticQuestsApi api = MysticQuestsApi.get();
}
```

`get()` throws `IllegalStateException` when MysticQuests is not running. The singleton is installed
after MysticQuests has finished starting and withdrawn before it shuts down, so a reference held
across a reload must be re-fetched.

MysticQuests does not need to be a compile-time dependency — the same surface is reachable
reflectively — but compiling against it is simpler if load order allows.

## State

Tags are flags an owner either has or does not. Variables are string key/value pairs on the same
owners. Both exist in five scopes:

| Scope | Owner | Example |
|---|---|---|
| `PLAYER` | player UUID | `9a3f…` |
| `GLOBAL` | collapses onto one owner | — |
| `ENTITY` | entity UUID | an NPC |
| `BLOCK` | `world:x:y:z` | a chest |
| `VOLUME` | `world:volumeId` | a trigger volume |

```java
api.addTag(playerId, "met_elder");
api.setVariable(playerId, "reputation", "12");
long total = api.incrementVariable(playerId, "boars_killed", 1);

api.addTag(StateScope.ENTITY, npcUuid.toString(), "guard");
api.setVariable(StateScope.GLOBAL, "", "festival", "open");
```

Reads return live unmodifiable views, so they allocate nothing and must not be modified.

All five scopes persist and survive restarts. Writes are coalesced per owner and flushed on a short
interval, plus synchronously on disconnect and shutdown — so a burst of changes in one tick costs one
write, not one per change.

## Visibility and targeting

```java
api.hideFrom(viewerId, targetId);   // a player or any UUID-backed entity
api.showTo(viewerId, targetId);
api.preventTargeting(playerId);     // NPCs stop being able to target them
```

Both are runtime-only and clear on disconnect: they describe a scene, not a save.

Hides are attributed to a source. The API applies them as `EVENT`, independently of any
condition-driven visibility rule in package content, so the two never cancel each other — a subject
stays hidden while any source still wants it hidden.

Hiding covers both sides of the engine's tracker: the viewer-side visible set, which despawns the
body, and the subject-side viewer map, which is what nameplates, skins, and model updates are sent
from. Filtering only the first is what used to leave a hidden player's nameplate floating in place.

### Sharing visibility with other mods

`HiddenPlayersManager` is one per-viewer set owned by the engine with no record of who added an
entry, and MysticVanish writes to the same set. MysticQuests therefore:

- never removes an entry it did not add itself;
- never removes one while MysticVanish still wants that player hidden;
- re-asserts its own hides once per tick, so another mod clearing the set costs a tick of visibility
  rather than silently defeating a quest.

Set `integrations.mysticVanish` to `false` to disable the deference. The bridge is bound reflectively
and is inert when MysticVanish is absent.

One engine limitation is worth knowing: a viewer with the client's "show entity markers" setting
enabled still sees hidden players, because the engine's hide pass skips that viewer entirely.
MysticQuests logs this once per viewer rather than failing silently.

## Registering event and condition types

This is how a mod adds behaviour that content can then use by name.

```java
api.registry().registerCondition("mymod:has_skill", ctx ->
        skills.level(ctx.playerId(), ctx.text("skill", "")) >= ctx.definition().integer("min", 1));

api.registry().registerEvent("mymod:grant_skill", ctx ->
        skills.grant(ctx.playerId(), ctx.text("skill", ""), ctx.definition().integer("levels", 1)));
```

Content then uses them anywhere a type is accepted — quest start and complete events, rewards,
conversation branches, objective gates, and trigger volumes:

```json
{
  "startConditions": [ { "type": "mymod:has_skill", "skill": "mining", "min": 3 } ],
  "rewards":         [ { "type": "mymod:grant_skill", "skill": "mining", "levels": 1 } ]
}
```

And from a trigger volume, through the generic action effect:

```json
{ "Type": "mysticquests:action", "Action": "mymod:grant_skill", "Value": "mining" }
```

Rules:

- **Namespace your ids.** Built-in names are refused outright, so a mod cannot silently take over
  `tag` or `giveItem` and change what existing content means.
- **First registration wins.** A second mod claiming the same id is refused and warned, rather than
  quietly replacing the first.
- **Register during start-up**, before content loads, so validation recognises the type.
- **Unregister on shutdown** with `unregisterEvent` / `unregisterCondition`.

Handlers get a `QuestActionContext`: the player, the originating package, the authored definition
including your own custom fields, whatever the trigger fired against, and a text resolver for
MysticQuests placeholders.

Handlers run synchronously on the thread that fired the action, usually the world thread mid-tick, so
keep them cheap. A handler that throws is logged and skipped — an event list keeps running, and a
condition denies rather than passing.

## Registering narrative action and condition types

The 2.0 narrative runtime (puzzle outputs, narrative transitions, condition trees) has its own,
typed registries:

```java
api.narrativeActions().register(NamespacedId.parse("mymod:grant_skill"), (context, parameters) -> {
    skills.grant(context.scope().actor(), parameters.path("skill").asText());
    return ActionResult.success();
});
api.narrativeConditions().register(NamespacedId.parse("mymod:has_skill"), (context, parameters) ->
        skills.level(context.actor(), parameters.path("skill").asText()) >= parameters.path("min").asInt(1));
```

Unlike the registry above, handlers return a typed `ActionResult` (`success`, `skipped`, `retryable`,
`terminal`) and run inside idempotent transitions, so a reward handler is never called twice for the
same step. Return `retryable` for "not now", for example while the player is offline: the step is
retried on their next join. Override `external()` to return true when the effect lives outside the
story session, so a checkpoint rollback never replays it. Ids must be namespaced outside
`mysticquests`, and the first registration wins. See
[docs/2.0/narrative-runtime.md](2.0/narrative-runtime.md#4-extending).

## Events

```java
AutoCloseable handle = api.subscribe(StateEvents.TagChange.class, change -> {
    if (change.scope() == StateScope.PLAYER && "met_elder".equals(change.tag())) {
        // react
    }
});
// on shutdown
handle.close();
```

Available in `org.hyzionstudios.mysticquests.event.StateEvents`:

| Event | Posted when |
|---|---|
| `TagChange` | a tag is added, removed, or cleared |
| `VariableChange` | a variable is set, removed, or cleared |
| `VisibilityChange` | a viewer's view of a player or entity changes |
| `TargetingChange` | a player gains or loses targeting protection |

Listeners run synchronously on the posting thread and are isolated from each other, so one throwing
listener cannot break dispatch for anyone else. `name` is null on a `CLEAR`, which covers the whole
owner.

## Built-in types worth knowing

Events: `tag`, `variable`, `giveItem`, `removeItem`, `runCommand`, `sendMessage`, `startQuest`,
`completeQuest`, `modifyMoney`, `notification`, `folder`, `party`, `if`, `ref`, `cancelConversation`,
`cancelQuest`, `hidePlayer`, `showPlayer`, `hideEntity`, `showEntity`, `preventTargeting`,
`allowTargeting`, `setCamera`, `sendTitle`, `actionBar`, `spawnNpc`, `despawnNpc`.

`spawnNpc` and `despawnNpc` do nothing unless MysticGeneration is installed and
`integrations.mysticGeneration` is on; they are reserved either way, so the names cannot be
registered by another mod and change meaning depending on what is installed.

Conditions: `tag`, `variable`, `questCompleted`, `questActive`, `permission`, `economy`,
`inConversation`, `inParty`, `partySize`, `and`, `or`, `not`, `ref`, `playerHidden`, `entityHidden`,
`targetingPrevented`, `nearEntity`.

### Target selectors

Anything with a `target` field accepts:

| Selector | Resolves to |
|---|---|
| `self` | the acting player (the default) |
| `context` | whatever the trigger fired against |
| `uuid:<id>` or a bare UUID | one explicit entity or player |
| `nearest` / `nearest:<radius>` | the closest tracked non-player entity, default radius 8 |
| `tag:<tag>` | every entity carrying that entity-scope tag |
| `generation` | the MysticGeneration NPC the trigger fired against, by stable identity |
| `generation:<definition>` | every live NPC spawned from that definition |
| `party` | every member of the acting player's party |
| `players` | every online player |

An unresolvable selector yields nothing rather than falling back to the player, so a typo never
applies an effect to the wrong subject.

## Canonical content schema

State is addressed through two types:

```json
{ "type": "tag",      "op": "add|remove",           "scope": "player", "target": "self", "tag": "met_elder" }
{ "type": "variable", "op": "set|remove|increment", "scope": "entity", "target": "nearest", "key": "spoken", "value": "true" }
```

Conditions use the same two types with no `op`, and `"invert": true` to negate a tag check.

The older aliases — `addTag`, `removeTag`, `globalTag`, `entityVariable`, `notTag`, and the rest — are
rewritten onto this form when content loads, so existing packages keep working unchanged and pay
nothing at runtime. New content should be written canonically.
