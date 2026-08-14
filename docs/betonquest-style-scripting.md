# BetonQuest-Style Scripting

MysticQuests accepts JSON, YAML, or a mixture of both inside each package. The scripting model is
BetonQuest-inspired but uses MysticQuests' Hytale-native objectives, events, state, conversations,
notifications, and UI.

## Package layout

```text
MysticQuests/
  templates/
    adventure_defaults/
      package.yml
  packages/
    coast/
      package.yml
      quests/main.yml
      conversations.yml
```

`package.yml` supports `enabled`, `version`, and `templates`. Package files and subfolders are merged
recursively. A nested folder containing its own `package.yml` becomes a child package. The first
template wins when templates overlap; the package itself always overrides templates.

Use a local ID (`intro`), a stored namespaced ID (`coast:intro`), or a cross-package reference
(`shared>intro`). Cross-package dashes map to package path separators.

## Named scripting elements

The following top-level maps are supported: `conditions`, `actions`/`events`, `objectives`, `quests`,
`conversations`, `items`, `cancelers`, `schedules`, `functions`, `notifications`, and `constants`.
Condition-driven `playerHiders` are also supported.

Definitions may be typed YAML objects or compact quote-aware instructions:

```yaml
conditions:
  ready: "tag tutorial_complete"

actions:
  announce: 'message "&aWelcome to %constant.region_name%, %player%!"'
  reward: "giveItem hytale:gold_ingot 3"

objectives:
  hunt: "kill hytale:wolf 5 name:'Hunt wolves' shared:true"
```

Single and double quotes preserve spaces. Backslash escapes include `\n`, `\r`, and `\t`. Named
arguments use `key:value`; numeric and boolean values are typed automatically.

## Placeholders and text

Script text resolves `%player%`, `%player.uuid%`, `%constant.name%`, `%tag.name%`,
`%globaltag.name%`, `%variable.key%`, `%point.key%`, `%condition.id%`, `%quest.id%`,
`%objective.id.amount%`, `%objective.id.left%`, `%randomnumber.whole.1~10%`, and
`%function.id.argument%`. Functions may use `$1` or `{0}` for arguments and may contain other
placeholders. Prefix a lookup with `package>` to address another package.

## Conversations, items, and notifications

Conversations are node/choice graphs. Node and choice conditions/actions can be inline typed values
or named references. All speaker text and choice text resolve script placeholders.

Named `items` wrap native Hytale item IDs and reusable defaults. `giveItem` and `removeItem` accept
the named item ID. Notification categories provide reusable `io`, style, color, title, and body
settings; an action selects one with `category: category_id`. `io: suppress` disables a category.

## Cancelers and schedules

A canceler can gate on named conditions, reset listed quests (or every active quest in its package),
remove package-scoped tags/variables, and run named cleanup actions. Run one with
`/mquest cancel <player> <package:canceler>` or the `cancelQuest` action.

Schedules support `realtime-daily` (`time: HH:mm`) and `realtime-cron` with five fields. Cron fields
accept wildcards, lists, ranges, and steps, plus `@hourly`, `@daily`, `@weekly`, `@monthly`, and
`@yearly`. Set an IANA `timezone`. Named schedule actions run once per matching minute for every
online player and update live after `/mquest reload`. `catchup: none|one|all` controls missed runs;
the last scan time is persisted across restarts.

## Parties

Mark an objective `shared: true` (or `party: true`) to copy matching progress from another party
member. Use `inParty` and `partySize` conditions. A `party` event runs nested actions for all party
members. MysticQuests consumes a `QuestPartyProvider` registered with MysticRPG when present and
otherwise tracks MysticGuilds party lifecycle events without creating a hard dependency.

## Player hiders

`playerHiders` apply native, per-viewer Hytale visibility. `sourceConditions` select the player to
hide and `targetConditions` select viewers who should not see that source. Each list accepts named
conditions or inline typed conditions. Visibility is reconciled on join, reload, and quest-state
changes, and MysticQuests restores every visibility change it owns during shutdown.

## Quest Studio

`/mquest admin` contains three workflows:

- **Quest Builder**: guided quest identity/objectives and advanced typed arrays.
- **Package Scripts**: load/create any package YAML/JSON file, validate, publish, live reload, and
  automatically roll back invalid edits.
- **Player State**: edit active/completed/abandoned state, objective progress, tags, and variables.

See `examples/packages/beton_style_adventure` and `examples/templates/adventure_defaults` for a
complete starting package.
