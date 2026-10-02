# MysticQuests: Server Guide

For server owners and staff: installing, upgrading to 2.0, configuring, permissions, staff tools and
troubleshooting. Players want the [player guide](players.md); quest authors want
[creating quests by hand](creating-quests.md), the [narrative runtime guide](2.0/narrative-runtime.md)
and the [content format](content-format.md).

## Requirements

- A Hytale server in the `0.6.x` range (the manifest declares `>=0.6.0 <0.7.0`).
- The built-in `Hytale:TriggerVolumes` plugin (required) and the built-in Audio plugin for story
  music (without it, music is switched off and logged; everything else works).

Optional mods. MysticQuests runs without any of them, and `/mq integrations` shows which are active:

| Mod | Adds |
|---|---|
| MysticRPG (party system) | Parties: shared objectives, `party` events, party conditions. 2.0 party stories also need a stable party id from it; until it supplies one they run per player (`/mq integrations` shows this). MysticGuilds parties are also recognised. |
| MysticGeneration | Story NPCs spawned per session (`mysticquests:entity.spawn`). |
| MysticVanish | Layered visibility: MysticQuests never reveals someone MysticVanish hides. |
| MysticNameTags | Nameplates follow story visibility (needs NameTags' MysticQuests support). |
| HyCitizens | Conversations on HyCitizens NPCs. |
| PlaceholderAPI, VaultUnlocked | Placeholders in quest text; economy rewards and conditions. |

## Installing and upgrading

1. Stop the server and **back up** `mods/MysticQuests/` (content, config and `data/`).
2. Replace the jar and start the server.
3. Existing v1 quest packages run unchanged. Nothing has to be rewritten.
4. Run `/mquest narrative migrate` to see what your content does under 2.0 (details under
   [Migration report](#migration-report)).

The 2.0 settings are added to `config.json` with defaults the first time it loads. Player progress
from v1 stays where it is and keeps working.

## Configuration

`mods/MysticQuests/config.json`. The full key list is in the [README](../README.md#20-narrative-runtime);
the 2.0 section is:

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

| Key | Set it to |
|---|---|
| `serverId` | A stable, unique name for this server. **Never change it** once players have progress: it owns server-wide story state. |
| `dataPath` | Where story state is saved. Point several servers at one shared folder so stories follow players across transfers. |
| `flushIntervalMillis` | How often changes are written. Quitting and shutdown always write at once. |
| `partyExitPolicy` | `fork`: a player leaving a party keeps a solo copy of its active stories. `detach`: the story stays with the party. |
| `openNamespaces` | Leave empty. Only for migrating content that uses undeclared tags or variables. |
| `subtitles` | `chat`, `title` (on-screen event title) or `off`. |
| `fallbackLocale` | The language every voice line must have; players with other languages hear it when theirs is missing. |

`/mquest reload` reloads content. A reload is all-or-nothing: if any quest or story content has an
error, nothing changes and the errors are listed. Only the first load at startup tolerates story
content errors, so a typo never stops the server from starting.

## Permissions

`mysticquests.admin` grants every staff command below.

| Permission | Grants |
|---|---|
| `mysticquests.command.journal` | Player commands: progress, track, untrack, abandon, skip. Give it to everyone. |
| `mysticquests.command.admin.reload` | `/mquest reload` |
| `mysticquests.command.admin.editor` | The in-game Quest Studio (`/mquest admin`) |
| `mysticquests.command.admin.quest` | Start, complete, re-accept and cancel quests for players. |
| `mysticquests.command.admin.entity` | Entity, block and HyCitizens binding tools. |
| `mysticquests.command.admin.volume` | Trigger volume state and tags. |
| `mysticquests.command.admin.debug` | Read-only inspection: `/mq debug`, `/mquest narrative` views, `/mq integrations`, the migration report. |
| `mysticquests.command.admin.narrative` | Story interventions: puzzle reset, trigger state, cutscenes, checkpoints, story restart, objectives, teleport, voice replay. Every one is audited. |
| `mysticquests.visibility.bypass` | `/mq visibility bypass`: see every story entity and hidden player while debugging. |
| `mysticquests.visibility.bypass.always` | Bypass switched on automatically at join. |

Bypass is presentation only. It shows staff everything without changing anyone's quest state or
what other players see.

## Staff tools

Inspecting (read-only):

| Command | Shows |
|---|---|
| `/mq debug <player> [export]` | Everything about one player's stories in one view: quests, sessions, tags, variables, trigger overrides, puzzle progress, story entities, doors and bridges, audio and the current scene. `export` saves it to `debug/` for a bug report. |
| `/mquest narrative sessions <player>` | Their story sessions. |
| `/mquest narrative puzzle <player> <puzzle>` | Which inputs they were dealt and which they have done. |
| `/mquest narrative media <player>` | What is playing and queued for them, and their music. |
| `/mquest narrative cutscene <player>` | The scene they are watching. |
| `/mquest narrative checkpoint <player>` | Saved checkpoints in their stories. |
| `/mq visibility status [player]` | Who is hidden from whom, and why. |
| `/mq integrations` | Which optional mods are active, and what is missing. |

Fixing (audited, with an optional reason at the end):

| Command | Does |
|---|---|
| `/mquest narrative puzzle <player> <puzzle> reset\|reroll` | Starts the puzzle over; `reroll` also deals new inputs. |
| `/mquest narrative trigger <player> <world:volume> enable\|disable\|clear [session\|player\|party\|global]` | Switches a trigger volume for that session, player, party or everyone. |
| `/mquest narrative checkpoint <player> rewind <label>` | Rewinds their story to a checkpoint. Items, money and commands already given are not given again. |
| `/mquest narrative story <player> <story> restart` | Starts the story over in a fresh session; the old one is kept for inspection. |
| `/mquest narrative objective <player> <quest> <objective> complete\|reset\|set <n>` | Changes v1 objective progress; completing the last one pays the quest rewards. |
| `/mquest narrative cutscene <player> <id> play` / `cutscene <player> skip` | Plays a scene for them, or ends theirs even if it is unskippable. |
| `/mquest narrative media <player> replay <media>` | Replays a voice line to them. |
| `/mquest narrative goto <world:volume>` / `goto <player> <puzzle> [n]` | Teleports you to a quest location, or to the n-th puzzle input that player was dealt. |

Every intervention is written to the audit log in the story data folder and to the server log.

## Data and backups

Under `mods/MysticQuests/`:

| Path | Holds |
|---|---|
| `packages/` | Quest content. |
| `config.json` | Configuration. |
| `data/` | v1 player progress (SQLite or JSON, per `storage`). |
| `data/narrative/` | 2.0 story state: sessions, scoped state, story entity claims, audit log. |
| `debug/`, `reports/` | Exports from `/mq debug ... export` and `/mquest narrative migrate export`. |

Back up `data/` with the server stopped, or while no one is online. Every stored story document
carries a schema version. A document that is unreadable or was written by a newer MysticQuests is
**quarantined**: it is never overwritten, the player runs on empty state for it, and the problem is
logged and listed in the migration report. Restore it from a backup or upgrade the plugin.

## Several servers

Give each server its own `serverId` and point them at one shared `dataPath`. Stories, puzzle
progress and party sessions then follow players between servers. Content should be the same release
everywhere; each session records the content version it was created and last played with.

## Migration report

`/mquest narrative migrate [export]` checks your content and stored state without changing either:

- **Unchanged:** how many v1 events, conditions and objectives run exactly as before.
- **Upgrade:** v1 patterns that now have a safer 2.0 form, with the file, location and replacement.
  For example, `spawnNpc` can become a story NPC no one else can interfere with, and `setCamera`
  sequences can become a skippable cutscene that always restores the camera.
- **Manual:** content files that cannot be read, and quarantined story documents.

Upgrades are optional. v1 player progress is not copied into 2.0 storage: v1 quests keep using
their own store, and 2.0 content reads it live.

## Troubleshooting

| Symptom | Check |
|---|---|
| A reload fails | The chat or console lists each error with its file and location. Nothing changed; fix and reload. |
| `MISSING_ASSET` warnings | A sound or music asset is not loaded. Voice lines still show subtitles. Check the asset pack is installed. |
| Story music never plays | The Audio plugin is missing (logged once), or the music asset is not loaded. |
| A player cannot see a quest NPC | It is probably a story entity owned by someone else's session; `/mq debug <player>` lists theirs. Staff can use bypass to see all. |
| Party stories act solo | `/mq integrations`: is the party provider active and does it support party ids? |
| A `gather` objective goes down | Expected: it counts held items, like Hytale's own gather quests, so dropping or using them lowers it until the quest completes. |
| A `kill` objective never counts | The target must be the exact NPC role name (`/npc role` shows it), and only the killing blow counts. |
| A player is stuck mid-story | `/mq debug <player>`, then rewind to a checkpoint or restart the story. |
| "quarantined" in the log | See [Data and backups](#data-and-backups). |
