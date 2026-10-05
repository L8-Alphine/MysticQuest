# MysticQuests: Player Guide

This page is for players. Server owners and staff want the
[server guide](server-guide.md); quest authors want the
[narrative runtime guide](2.0/narrative-runtime.md).

## Commands

| Command | What it does |
|---|---|
| `/quest` | Opens the quest board: quests you can take, grouped by kind, with their difficulty, party size and the rewards they tell you about. Some quests you cannot take yet show what you need to do first. |
| `/journal` | Opens your Journal: the quest you track, your other quests by kind, the stories you are part of, and what you have finished. It also holds your quest settings. |
| `/mquest progress` | Shows your progress on active quests in chat. |
| `/mquest track <quest>` | Pins a quest to your on-screen tracker. `/mquest untrack` unpins it. |
| `/mquest hud <auto\|compact\|expanded\|hidden>` | Chooses how much the tracker shows. `auto` expands it only when the current step has more than one objective; `hidden` removes it (a puzzle card still appears while you solve a puzzle). Without a mode, tells you the current one. Saved for you. |
| `/mquest abandon <quest>` | Drops a quest. You can usually take it again from the board. |
| `/mquest skip` | Skips the story scene you are watching, when the scene allows it. |
| `/mquest audio` | Shows your story audio settings. `/mquest audio voice fr-FR` plays voice lines in French when they are recorded in it (`auto` follows your game language); `/mquest audio subtitles off` hides story subtitles. Both are saved. |

`/mq` works anywhere `/mquest` does.

## Your quest tracker

The tracker in the top-right corner shows the quest you are tracking: what to do right now, how far
along that is, and how far along the whole quest is. When an objective has a place, it is marked on
your world map and the tracker says so. A marker for an area to search sits in the middle of that
area, not on the exact spot.

The tracker gets out of the way while you are in a conversation. When you work on a story
puzzle, a puzzle card appears above your hotbar with a hint and your progress; it never tells you
which keys or switches are yours — that is the puzzle.

## Your Journal

`/journal` opens on the quest you are tracking. The rail on the left lists your sections: the tracked
quest, story quests, side quests, contracts and so on, then the stories you are part of, then what
you have completed or abandoned. Pick a section to see its quests as cards in the middle, and a card
to see, on the right:

- what to do right now, and the step you are on;
- the story so far and every objective, grouped by step;
- the rewards the quest has told you about (some keep their rewards a surprise);
- when you accepted it, and when you finished it.

From there you can track a quest, stop tracking it, or abandon it (press Abandon, then Confirm
abandon). A story shows the milestones you have reached in it, never what comes next.

**Quest settings**, at the bottom of the rail, are saved for you: how much of the tracker shows
(Automatic, Expanded, Compact or Hidden), story subtitles, your voice-line language, and quest
pop-ups when the server uses them. **Restore defaults** puts them all back.

On the **quest board** (`/quest`), the chips along the top filter by kind, and each card has its own
**Accept**. In a **conversation**, choices are numbered along the bottom of the line; **Transcript**
shows what was said so far, and **Leave** ends the conversation.

## Your story is your own

Some quests run as a **story**: a version of the world that belongs to you, or to your party.

- **Puzzles are dealt to you.** In a puzzle like "find four of the ten druid keys", you and another
  player are given different keys. Your choice is saved, so logging out or a server restart never
  reshuffles it, and keys that are not yours do nothing for you.
- **Story characters are yours.** A boss or guide in your story can only be seen, targeted, hit or
  talked to by you (or your party). Two groups in the same room can each fight their own copy of the
  boss without getting in each other's way.
- **Doors and bridges can differ.** A sealed door may already be open for you because you solved
  its puzzle, while it stays closed for someone who has not. You walk through what is open for you,
  and you bump into what is closed.
- **Story audio is yours.** Voice lines, sound effects and music from your story play only for you
  or your party. Subtitles for voiced lines appear in chat or on screen, depending on the server.
  Voice lines play in your game language when a recording exists, otherwise in the server's
  default language. You can choose a different voice language with `/mquest audio voice <language>`
  and keep reading subtitles in your game language, or turn subtitles off with
  `/mquest audio subtitles off`.

## Playing with a party

- When you are in a party, shared stories belong to the whole party: one set of puzzle keys, one
  boss, one story for everyone in it. (This needs the server's party system to support it; otherwise
  each member plays their own copy.)
- If you leave the party mid-story, you normally keep your own copy of where the party had got to
  and can carry on alone. Some servers choose to keep the story with the party instead.

## Story scenes

- Short story scenes may move your camera and play voice lines.
- `/mquest skip` ends a scene early when it is skippable. Skipping never loses progress: whatever the
  scene was meant to change still happens, and your camera is put back.
- If you disconnect during a scene, it is finished for you when you come back, so you never return
  to a scene stuck halfway.

## Logging out and server restarts

Your quests, puzzle progress and story state are saved. Rewards are never given twice, even when a
server restarts in the middle of handing them out. If something you were owed could not be given
while you were offline, it is given when you next join.

## Your quests on the web

If the server runs MysticIdentity, the player portal has a **Quests** section: your active quests
with the step you are on and what is left, your quest history, and the story milestones you have
reached. It is read-only and never shows puzzle answers or anything the game keeps secret.

## Something looks wrong?

Tell a staff member which quest and what you expected. Staff can look at exactly where your story
stands and fix it without affecting anyone else's.
