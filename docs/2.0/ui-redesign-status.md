# MysticQuests 2.0 UI / HUD Redesign — Status

Tracks the *MysticQuests 2.0 UI / HUD Redesign Bible* (Hyzion Studios, October 2026) against what
ships. The Bible is a design standard; where it and the local Hytale Workshop index (engine
`release/0.6.8`) disagree, the index wins, and the reason is recorded below.

Status key: **Done** · **Partial** · **Not started** · **Engine-limited**.

## 2026-10-05: rebuilt after the first look in game

The first time the redesigned pages were opened in a client, buttons and forms did nothing, the
layout was broken, and the look was still the v1 "reliquary" art rather than the Bible's. The pages
were rebuilt on the patterns the engine's own admin pages use and MysticRPG has proven in game; the
rules are in [hytale-native-ui.md](../hytale-native-ui.md#client-rules-for-interactive-pages).

| Symptom | Cause | Now |
|---|---|---|
| Forms did nothing; player tools said "Unknown player" | Field values were bound with plain keys, so the client sent the selector text instead of the value; the checkbox payload failed to decode and froze the page | `@`-keyed value bindings; a test fails the build on a plain key |
| Clicks stopped working | Some paths never answered a click (the client locks until a page packet arrives), and rows were inline markup strings | `MysticQuestsPage` answers every event, even undecodable ones, and re-sends every binding; rows are template documents appended and addressed by position |
| Layout broken | A 56-unit header inside the vanilla container's 38-unit title strip, and frames taller than a 720p screen | 1200 x 660 frames centred by the page overlay, as the engine's pages are |
| Off-style | v1 panel and button textures under the 2.0 palette | Rounded, pre-coloured nine-slice art in the Bible's palette (`Assets/v2/`, `tools/build_ui_v2_textures.py`); every page, row, both HUDs and the Studio's dark theme use it |

Also fixed on the way: the HUD documents imported tokens the new theme no longer had, and three
unwired design shells did too. Because the client loads every shipped document, either would have
stopped custom UI for every mod on the server; the HUDs now import nothing, and the shells were
removed (they remain in git history).

Player state can now be inspected and corrected from three places through one audited service
(`service/PlayerStateAdmin`): `/mquest player`, the in-game admin's **Players** view, and the web
Studio's **Players** page.

## Build order (Bible §14.1)

| Phase | Deliverable | Status |
|---|---|---|
| 1. Contracts | View models, UI regions, renderer capabilities | **Partial.** `QuestHudViewModel` (tracker), `QuestPuzzleHudState` (puzzle card) and `QuestHudCoordinator` (which layer shows, in which composition) exist. No package/instance revisions yet, and no action-descriptor contract beyond server-issued dialogue choice tokens. |
| 2. Native provider | Tracked HUD, transitions, Journal, Dialogue, notification bridge | **Done.** Tracker (compact / expanded / hidden), puzzle card, cinematic dialogue page, semantic transition cards, the redesigned Journal and quest board. |
| 3. Session states | Transfer/recovery, party contribution, puzzle and world-presentation UI | **Partial.** Puzzle card for the actor and, for party puzzles, every online party member; HUD steps aside during story cutscenes. |
| 4–5. Creator Studio | Web studio | **Done** as the web Studio (docs/studio.md): quest map, dialogue, puzzle and cutscene editors, validation, versioned publishing. |
| 6. Live tools | Session browser, trace, diagnostics | **Done**: in game (`/mq debug`, `/mq integrations`, `/mq visibility`), the Studio's Live Sessions page, and audited player corrections (§9.1) from `/mquest player`, `/mquest admin` and the Studio's Players page. |
| 7. Themes / accessibility | High clarity, reduced motion, scale, RTL | **Partial.** Saved settings for tracker density (including Hidden), story subtitles, voice language and quest pop-ups (the only motion this UI has). High clarity, scale and RTL are not built. Every page, both HUD layers and the Studio's dark theme use the Bible's palette and rounded card language. |
| 8. Migration & hardening | | **Not started.** |

## What shipped

| Bible | Implementation |
|---|---|
| §6.1 Default tracked HUD | `Hud/MysticQuestsQuestHud.ui` `#CompactTracker`, `QuestHud`. Category and state badges, title, step line, one verb-first primary objective with its own meter, whole-quest progress, guidance line. |
| §6.2 Expanded tracker | Same document, `#ExpandedTracker`: primary plus up to three supporting rows, Journal hint. Switched by `Visible` patches, never by re-adding the HUD. `/mquest hud <auto\|compact\|expanded>`. |
| §6.3 Focus / navigation | Objective `marker` blocks become a per-player world-map marker (`QuestHudService#collectMarker`, a keyed `WorldMapManager` marker provider, modelled on vanilla `ObjectiveMarkerProvider`). The tracker's guidance line points at it. No centre-screen focus card: navigation is the map's job. Search areas say "search the marked area" rather than promising a point. |
| §6.4 Puzzle HUD | `Hud/MysticQuestsPuzzleHud.ui`, `QuestPuzzleHud`: its own HUD layer, bottom-centre. Rule-specific hint and status for all ten rule types, never input ids, candidates, seed or order. Dismisses 6 s after completion or after 60 s idle. |
| §6.5 Cinematic dialogue | `ConversationPage`: portrait card, voice badge, speaker and line, numbered choices two to a row, a transcript panel, Leave. Choices are server-issued opaque tokens re-issued per render; the client cannot pick a branch by index. The tracker hides while a conversation is open. |
| §6.8 Semantic transitions | `QuestTransitions` (pure diff) + `QuestTransitionService`: accepted / new step / progress / complete, each with its own wording and style, per-quest notification tags so progress updates one toast in place. Opt-in: `ui.transitionCards`. Baseline on join, so reconnects never replay. |
| §10.3 "replace, don't spam" | Authored `notification` events accept a `tag` too. |
| §6.5 / §6.6 A scene owns the screen | Story cutscenes hide the tracker and puzzle card for the session's audience (`CutsceneHudBridge`, a `QuestCutsceneService.Listener`). Cinematic reasons are kept per source in `QuestHudCoordinator`, so a conversation ending mid-scene cannot bring the HUD back early. |
| §7.1-7.2 Quest Journal | `Pages/JournalPage.ui`, `MysticQuestJournalPage`, `JournalModel`: a rail of sections (tracked, quests by authored `category`, the player's 2.0 stories, completed newest first, abandoned) and the section's quests as cards, and the detail (category and state badges, step line, current objective with meter and count, story recap, objectives by step with Done / Now / To do, revealed `rewardText`, a dated timeline, Track / Stop tracking / Abandon with confirmation), laid out like the concept screen. Opens on the tracked quest, so the HUD's "open the Journal" lands on the same quest. Stories show the milestones reached in them, never what comes next. |
| §7.4 Quest UI settings | In the Journal, four cards like the concept screen: quest tracker (Automatic / Expanded / Compact / Hidden), story audio (subtitles, voice language), motion (quest pop-ups) and Restore defaults. Saved as reserved player variables in the story state (`PlayerUiPreferences`, `QuestMediaService#preferences`), so they survive logout and restarts. `/mquest hud` and `/mquest audio` change the same settings. |
| §7.3 Quest board | `Pages/QuestMenuPage.ui`, `QuestMenuPage`, `QuestBoardModel`: category filter chips and two columns of cards outlined in their category's colour, each separating category and difficulty, party size and revealed rewards, from authored `difficulty`, `partySize`, `rewardText`, with its own Accept; locked cards only for quests with `lockedText`, showing that text and nothing else. Accept is revalidated by `startQuest`. |
| §9.1 Staff tools | `Pages/QuestAdminPage.ui`, `QuestAdminPage` (`/mquest admin`): a player inspector (online list, find by name or UUID, Quests / Tags and variables / Story state, reason field, per-row actions with second-press confirmation, a clear dialog with selectable parts), the quest builder and package scripts. `/mquest player` and the Studio's Players page do the same through `PlayerStateAdmin`, which needs a reason for every change and audits it. |
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

1. **See it in a client.** The rebuilt pages follow proven patterns and pass every markup check, but
   have not been seen running yet. First: `/journal` (pick a section, a card, Track, Settings and a
   setting), `/quest` (a chip, Accept), a conversation (a choice, Transcript, Leave), `/mquest admin`
   (pick a player, a tab, a reasoned change, the clear dialog), at 720p and 1080p, and the two HUD
   layers beside MysticRPG's. If a page fails to open, the client log names the document; if custom
   UI stops loading for everyone, bisect with `-PskipUiDocuments` (see hytale-native-ui.md).
2. **High Clarity (§7.4, §11):** a high-contrast composition of the tracker and puzzle card, chosen
   by a saved setting.
3. **Story tracker on the HUD.** Stories are in the Journal; the HUD tracker still reads v1 quests only.
4. **Revisions (§10.3), only if transfers arrive.** Reviewed 2026-10-04 and mostly covered by
   design: a reload loads and compiles before anything is swapped and the HUD reconciles after the
   swap, so the old tracker stays up throughout and a failed reload never touches it; every render
   reads current state on the player's world thread, so no stale render can overtake a newer one.
   What is left is a cross-server transfer, which has no in-process window to cover today.

## Verification

- Every tag, property and enum value in every page, row and HUD document was checked against 9,758
  documents from the client's `UserData/CachedAssets` plus the base game's own (excluding
  MysticQuests' own, since the cache keeps old broken versions; a value must appear in at least
  three documents), every property against the elements that carry it in those documents, every
  block for a property set twice, every `$MQ.@Name` for a definition, and with `validate_hytale_ui`.
  `UiMarkupTest` checks the structural and client rules; `JournalModelTest`, `QuestBoardModelTest`,
  `PlayerStateAdminTest` and `StudioServiceTest` the logic behind the pages.
- Engine calls were checked with `validate_hytale_code_refs` against `release/0.6.8`.
- None of this has been seen in a running client yet. The first in-game check should be the two
  HUD layers' positions at 720p and 1080p next to MysticRPG's HUD.
