# BetonQuest To MysticQuests Mapping

MysticQuests supports BetonQuest-style compact scripting inside YAML while preserving typed JSON/YAML objects for visual tooling and validation.

| BetonQuest idea | MysticQuests V1 equivalent |
| --- | --- |
| Packages | `packages/<package-id>/` directories |
| Conditions | Named quote-aware instructions or typed objects |
| Events | Named `actions`/`events`, inline typed objects, and cross-package references |
| Tags | Player tag storage and `tag` conditions/events |
| Objectives | Quest `objectives` arrays |
| Conversations | `conversations.json` with nodes and choices |
| NPC start points | `entity.uuid`, with `entity.type` and `entity.name` fallback |
| Journal text | Quest `displayName`, `description`, and objective display names |
| Variables | Player and quest variables |
| GlobalTag/GlobalPoint | `scope: "global"` tags and variables |
| NPC/object state | `scope: "entity"` tags and variables |
| Block state | `scope: "block"` tags and variables |
| Trigger volume state | `scope: "volume"` tags and variables |
| Quest items/rewards | `giveItem`, `removeItem`, `modifyMoney`, `notification`, and custom bridge events |
| Templates | `templates/<id>/` merged through a package manifest |
| Quest cancelers | Named `cancelers`, `/mquest cancel`, and `cancelQuest` actions |
| Schedules | Daily or five-field cron schedules with IANA time zones |
| Parties | Shared objectives, party conditions, and party action fan-out |

The current syntax and complete sample are documented in
[BetonQuest-style scripting](betonquest-style-scripting.md).

## Conversation Pattern

BetonQuest often starts dialogue from an NPC. MysticQuests binds a conversation to a Hytale entity:

```json
{
  "id": "guard_warning",
  "speaker": "Gate Guard",
  "entity": {
    "uuid": "paste-from-mquest-entity-uuid",
    "type": "NpcEntity",
    "name": "Gate Guard"
  },
  "nodes": [
    {
      "id": "start",
      "text": "You need permission to enter.",
      "choices": [
        { "text": "I have it.", "conditions": [{ "type": "tag", "tag": "gate_pass" }], "next": "enter" },
        { "text": "I'll come back.", "next": "end" }
      ]
    },
    {
      "id": "enter",
      "text": "Then go ahead.",
      "events": [{ "type": "addTag", "tag": "entered_city" }],
      "choices": [{ "text": "Thanks.", "next": "end" }]
    }
  ]
}
```

Use `/mquest entity uuid` while looking at a non-player entity to inspect its UUID, display name, and implementation type. Use `/mquest entity bind <conversation>` to print a ready-to-paste JSON binding snippet.

## Scoped State Pattern

BetonQuest has player tags, global tags, points, and many conditions that target world objects. MysticQuests represents those as typed scoped JSON:

```json
{ "type": "globalTag", "tag": "festival_open" }
```

```json
{ "type": "setVariable", "scope": "entity", "key": "spoken_to", "value": "true" }
```

```json
{ "type": "volumeVariable", "key": "enabled", "operator": "eq", "value": "true" }
```

MysticQuests remains authoritative for player state. If HyExtras is available, player tag and variable mutations are exported to HyExtras so trigger content can reuse the same player progression signals.

## HyExtras Trigger Pattern

HyExtras trigger volumes can call into MysticQuests without making HyExtras a required dependency. Native Hytale trigger events still progress `triggerEnter` and `triggerExit` objectives; the HyExtras bridge is for trigger configs that need to read or mutate MysticQuests scoped state.

```json
{
  "type": "mysticquests:has_tag",
  "scope": "player",
  "tag": "guild_member"
}
```

```json
{
  "type": "mysticquests:set_variable",
  "scope": "volume",
  "key": "visits",
  "value": "1"
}
```

```json
{
  "type": "mysticquests:increment_variable",
  "scope": "block",
  "key": "uses",
  "amount": 1
}
```

Volume owners use `worldName:volumeId` when a world name is available, otherwise `volumeId`. Block owners use `worldUuid:x:y:z`. HyExtras-owned volume tags stay separate from MysticQuests volume tags unless a trigger bridge effect writes them into MysticQuests.
