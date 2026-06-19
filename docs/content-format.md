# MysticQuests Content Format

Quest content lives in package directories under the configured `packagesPath`.

```text
packages/
  tutorial/
    quests.json
    conditions.json
    events.json
    variables.json
    conversations.json
```

IDs are namespaced as `package:id`. Inline references inside the same package may use the local `id`; stored quest state always uses the namespaced ID.

## Quest Fields

- `id`: local quest ID.
- `displayName`: player-facing name.
- `description`: journal text.
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

```json
{ "id": "craft_sword", "type": "craft", "item": "hytale:wooden_sword", "amount": 1 }
```

```json
{ "type": "setVariable", "scope": "player", "key": "quest_stage", "value": "2" }
```

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
        "name": "Elder Rowan"
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

## Quest HUD

MysticQuests shows one pinned active quest in a `CustomUIHud` while the player has active quests. If the player has not pinned a quest, the runtime picks the oldest active quest deterministically. Players can control the pin with `/mquest track <quest>` and `/mquest untrack`.

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
