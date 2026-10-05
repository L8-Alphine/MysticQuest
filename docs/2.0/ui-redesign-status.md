# MysticQuests 2.0 UI / HUD Redesign — Status

Tracks the *MysticQuests 2.0 UI / HUD Redesign Bible* (Hyzion Studios, October 2026) against what
ships. The Bible is a design standard; where it and the local Hytale Workshop index (engine
`release/0.6.8`) disagree, the index wins, and the reason is recorded below.

Status key: **Done** · **Partial** · **Not started** · **Engine-limited**.

## Build order (Bible §14.1)

| Phase | Deliverable | Status |
|---|---|---|
| 1. Contracts | View models, UI regions, renderer capabilities | **Partial.** `QuestHudViewModel` (tracker), `QuestPuzzleHudState` (puzzle card) and `QuestHudCoordinator` (which layer shows, in which composition) exist. No package/instance revisions yet, and no action-descriptor contract beyond server-issued dialogue choice tokens. |
| 2. Native provider | Tracked HUD, transitions, Journal, Dialogue, notification bridge | **Done.** Tracker (compact / expanded / hidden), puzzle card, cinematic dialogue page, semantic transition cards, the redesigned Journal and quest board. |
| 3. Session states | Transfer/recovery, party contribution, puzzle and world-presentation UI | **Partial.** Puzzle card for the actor and, for party puzzles, every online party member; HUD steps aside during story cutscenes. |
| 4–5. Creator Studio | Web studio | **Done** as the web Studio (docs/studio.md): quest map, dialogue, puzzle and cutscene editors, validation, versioned publishing. |
| 6. Live tools | Session browser, trace, diagnostics | **Done**: in game (`/mq debug`, `/mq integrations`, `/mq visibility`) and the Studio's read-only Live Sessions page. |
| 7. Themes / accessibility | High clarity, reduced motion, scale, RTL | **Partial.** Saved settings for tracker density (including Hidden), story subtitles, voice language and quest pop-ups (the only motion this UI has). High clarity, scale and RTL are not built. The Journal and quest board use the 2.0 palette; the in-game studio page still uses v1 tokens. |
| 8. Migration & hardening | | **Not started.** |

## What shipped

| Bible | Implementation |
|---|---|
| §6.1 Default tracked HUD | `Hud/MysticQuestsQuestHud.ui` `#CompactTracker`, `QuestHud`. Category and state badges, title, step line, one verb-first primary objective with its own meter, whole-quest progress, guidance line. |
| §6.2 Expanded tracker | Same document, `#ExpandedTracker`: primary plus up to three supporting rows, Journal hint. Switched by `Visible` patches, never by re-adding the HUD. `/mquest hud <auto\|compact\|expanded>`. |
| §6.3 Focus / navigation | Objective `marker` blocks become a per-player world-map marker (`QuestHudService#collectMarker`, a keyed `WorldMapManager` marker provider, modelled on vanilla `ObjectiveMarkerProvider`). The tracker's guidance line points at it. No centre-screen focus card: navigation is the map's job. Search areas say "search the marked area" rather than promising a point. |
| §6.4 Puzzle HUD | `Hud/MysticQuestsPuzzleHud.ui`, `QuestPuzzleHud`: its own HUD layer, bottom-centre. Rule-specific hint and status for all ten rule types, never input ids, candidates, seed or order. Dismisses 6 s after completion or after 60 s idle. |
| §6.5 Cinematic dialogue | `ConversationPage`: speaker rail, subtitle band, voice badge, scrolling choices, recent transcript. Choices are server-issued opaque tokens re-issued per render; the client cannot pick a branch by index. The tracker hides while a conversation is open. |
| §6.8 Semantic transitions | `QuestTransitions` (pure diff) + `QuestTransitionService`: accepted / new step / progress / complete, each with its own wording and style, per-quest notification tags so progress updates one toast in place. Opt-in: `ui.transitionCards`. Baseline on join, so reconnects never replay. |
| §10.3 "replace, don't spam" | Authored `notification` events accept a `tag` too. |
| §6.5 / §6.6 A scene owns the screen | Story cutscenes hide the tracker and puzzle card for the session's audience (`CutsceneHudBridge`, a `QuestCutsceneService.Listener`). Cinematic reasons are kept per source in `QuestHudCoordinator`, so a conversation ending mid-scene cannot bring the HUD back early. |
| §7.1-7.2 Quest Journal | `Pages/JournalPage.ui`, `MysticQuestJournalPage`, `JournalModel`: a rail of sections (tracked, quests by authored `category`, the player's 2.0 stories, completed newest first, abandoned) and a detail pane (category and state badges, current step, a current-objective card, recap, objectives by step, revealed `rewardText`, a short timeline, Track / Stop tracking / Abandon with confirmation). Opens on the tracked quest, so the HUD's "open the Journal" lands on the same quest. Stories show the milestones reached in them, never what comes next. |
| §7.4 Quest UI settings | In the Journal: tracker density (Auto / Compact / Expanded / Hidden), story subtitles, voice language, quest pop-ups. Saved as reserved player variables in the story state (`PlayerUiPreferences`, `QuestMediaService#preferences`), so they survive logout and restarts. `/mquest hud` and `/mquest audio` change the same settings. |
| §7.3 Quest board | `Pages/QuestMenuPage.ui`, `QuestMenuPage`, `QuestBoardModel`: cards grouped by category, each separating difficulty, party size, availability and revealed rewards, from authored `difficulty`, `partySize`, `rewardText`; locked cards only for quests with `lockedText`, showing that text and nothing else; a preview of the facts the author provided. Accept is revalidated by `startQuest`. |
| §4.2 Category badge | The tracker's badge shows the quest's `category` (STORY, SIDE, CONTRACT, ...), QUEST when it has none. |
| §6.6 Party contribution | A party puzzle's card reaches every online member when shared progress changes (accepted, released, reset, solved), with teammate wording ("A party member moved the mechanism."). A locked or ignored input stays with the player who made it. |

## Decisions that differ from the Bible, and why

- **Two HUD documents, not one.** A HUD document cannot place blocks in two corners: `Anchor`
  offsets are relative to where the layout puts an element. The tracker (top-right) and puzzle card
  (bottom-centre) are separate `CustomUIHud` layers — the same lesson as MysticRPG's vitals and
  experience bar. See `docs/hytale-native-ui.md`.
- **No focus card (§6.3).** The world map already does navigation; a second centre card would
  compete with the native UI. Distance readouts are not shown: the HUD is not re-sent every tick.
- **Transition cards are opt-in.** Existing content sends its own `notification` events at quest
  start and completion; announcing by default would show both.
- **Notification tags are 0.6.8, not "Update 6".** `NotificationUtil#sendNotification(…, tag)` exists
  in `release/0.6.8`; same-tag toasts are replaced in place.
- **Settings live in the Journal, not a separate page.** A new full-screen document is the riskiest
  kind of UI change here (one bad value breaks every mod's UI), so the settings reuse the Journal's
  detail pane and its proven row and button patterns.
- **No High Clarity yet.** The engine has no way to restyle an element at runtime; the base game
  switches between pre-styled variants with `Visible`. High Clarity therefore needs a second,
  high-contrast composition of the HUD documents, which is its own piece of work.
- **"Reduced motion" is the pop-up switch.** The only motion MysticQuests puts on screen is its
  quest update cards; a player can turn those off without losing the tracker's state.

## Next, in order

1. **See it in a client.** None of the redesigned surfaces has been seen running yet. Check the
   Journal at 720p and 1080p, the two HUD layers beside MysticRPG's, and the settings buttons.
2. **High Clarity (§7.4, §11):** a high-contrast composition of the tracker and puzzle card, chosen
   by a saved setting.
3. **Story tracker on the HUD.** Stories are in the Journal; the HUD tracker still reads v1 quests only.
4. **Revisions (§10.3), only if transfers arrive.** Reviewed 2026-10-04 and mostly covered by
   design: a reload loads and compiles before anything is swapped and the HUD reconciles after the
   swap, so the old tracker stays up throughout and a failed reload never touches it; every render
   reads current state on the player's world thread, so no stale render can overtake a newer one.
   What is left is a cross-server transfer, which has no in-process window to cover today.

## Verification

- Every tag, property and enum value in the documents above, and in the rows the Journal builds in
  Java, was checked against 9,758 documents from the client's `UserData/CachedAssets` (excluding
  MysticQuests' own, since the cache keeps old broken versions; a value must appear in at least
  three documents, and a value under 1% of a property's uses is flagged), and with
  `validate_hytale_ui`. `UiMarkupTest` checks the structural rules; `JournalModelTest` and
  `QuestBoardModelTest` the Journal's and board's grouping, selection and what they reveal.
- Engine calls were checked with `validate_hytale_code_refs` against `release/0.6.8`.
- None of this has been seen in a running client yet. The first in-game check should be the two
  HUD layers' positions at 720p and 1080p next to MysticRPG's HUD.
