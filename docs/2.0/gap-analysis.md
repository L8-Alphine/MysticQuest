# MysticQuests 2.0 — Phase 0 Gap Analysis

This document records what MysticQuests already does, what the 2.0 specification asks for, and what
the local Hytale Workshop index (engine `release/0.6.8`, the newest build inside the manifest's
`ServerVersion` range `>=0.6.0 <0.7.0`) says the engine can actually support. Every engine claim
below was looked up in that index, not recalled; the class or method is cited so it can be
re-checked when the target version moves.

Status key: **Exists** — implemented and in use · **Partial** — a v1 mechanism covers part of it ·
**Missing** — nothing yet · **Engine-limited** — the engine does not offer what the spec wants.

## 1. Current inventory (v1)

| Area | Where | Notes |
|---|---|---|
| Content packages | `content/QuestContentLoader` | JSON/YAML packages, templates, BetonQuest-style instructions, atomic reload. Validation fails the whole reload on any error. |
| Canonical state schema | `content/ContentSchema` | Legacy aliases (`addTag`, `globalVariable`, …) rewritten onto `tag` / `variable` at load. |
| Tags / variables | `state/MysticStateStore`, `service/ScopedStateService` | Five scopes (`PLAYER`, `GLOBAL`, `ENTITY`, `BLOCK`, `VOLUME`). Tags are un-namespaced strings; variables are untyped strings. Unknown scope strings silently fall back to `PLAYER` (`StateScope#parse`). |
| Persistence | `storage/*`, `state/StateWriteQueue` | JSON or SQLite. Coalesced writes, flushed on disconnect and shutdown. SQLite schema is versioned (`SCHEMA_VERSION = 2`); JSON player files are not. |
| Quest runtime | `service/PlayerQuestService` | Per-player active/completed/abandoned quests, flat objective counters, optional stages (presentation only). No quest graph, no checkpoints, no idempotency on rewards. |
| Conditions | `PlayerQuestService#evaluateCondition` | `and` / `or` / `not` plus leaf types and registry fall-through. No counting combinators. A failing custom handler denies. |
| Actions ("events") | `PlayerQuestService#executeEvents` | Large switch plus `MysticQuestsRegistry` fall-through. No result type: failures are logged, never reported to the caller. Unknown types at execution are logged. |
| Extension API | `api/MysticQuestsApi`, `api/MysticQuestsRegistry` | Third-party event and condition types, state events, visibility and targeting. |
| Trigger volumes | `integration/triggervolumes/*`, `hytale/HytaleEventBridge#onTriggerVolume` | Nine effects and two conditions registered in `setup()` so volumes never drop them on decode. `triggerEnter` / `triggerExit` objectives come from the global `TriggerVolumeEvent`. No per-player or per-session activation. |
| Player / entity visibility | `service/VisibilityService`, `hytale/EntityVisibilitySystem`, `hytale/NameplateVisibilitySystem` | Per-viewer, attributed to a `Source` (`EVENT`, `RULE`), defers to MysticVanish, re-asserts each tick. Runtime-only. |
| NPC targeting | `service/TargetingPreventionService`, `hytale/TargetingPreventionSystem` | Per player, against every NPC. Not owner-scoped. |
| Parties | `integration/MysticPartyIntegration` | Members from MysticRPG or MysticGuilds. Only MysticGuilds supplies a stable party id. |
| NPC integration | `integration/MysticGenerationBridge`, `HyCitizensBridge` | Reflective, lazy, optional. |
| UI | `ui/*` | Quest board, journal, HUD, conversation page, in-game Quest Studio. |
| Commands | `command/MQuestCommand` | Admin, state, volume, debug. |
| Tests | `src/test` | 115 tests, all passing at the start of this work. |

## 2. Engine findings (Hytale Workshop index, `release/0.6.8`)

### Trigger volumes

- Types are registered through `TriggerVolumesPlugin#registerEffectType`, `#registerConditionType`,
  `#registerRuleType` and `#registerEventType`. Each throws on a duplicate id. The plugin's Javadoc
  requires registration in `setup()` and the `Hytale:TriggerVolumes` manifest dependency, which is
  what v1 already does.
- `TriggerEffect.BASE_CODEC` contributes `Event`, `Interval`, `Delay` and `Entry`. `TriggerContext`
  carries the entity ref, store, event type, volume, spatial volumes, actor position, **signal tags**,
  block position and id, and interaction type.
- `TriggerEventType` is an open interned registry: `VOLUME_CREATE`, `ENTER`, `EXIT`, `TICK`,
  `TAG_ADDED`, `TAG_REMOVED`, `BLOCK_PLACED`, `BLOCK_BROKEN`, `BLOCK_USED`, `SIGNAL_RECEIVED`,
  `ENTITY_DIED`, plus plugin-registered names.
- Enable/disable is **global per volume**: `VolumeEntry#setEnabled`, `TriggerVolume#setEnabled`,
  `GroupEntry#setEnabled`, and the builtin `EnableVolumeEffect` / `DisableVolumeEffect`. There is no
  per-player enable flag. Conditions, however, are tested per triggering entity, and a volume can
  carry `RejectionEffects` for when a condition fails.
- **Rules** (`TriggerRule`) are always-active data enforced by systems that query the manager. A
  plugin registering a rule type must register its own enforcement system.
- `registerAssetSource` / `registerAssetField` turn an effect field into a picker in the in-game
  editor.

**Consequence.** Per-player, per-party and per-session activation must be a MysticQuests logical
layer, as §5.2 of the spec anticipates. The native hook is a MysticQuests trigger *condition* that
asks that layer whether the volume is enabled for the triggering player. The condition runs per
entity, so the native volume becomes per-audience without ever being toggled globally.

### Player and entity visibility

- `HiddenPlayersManager` (`hidePlayer`, `showPlayer`, `isPlayerHidden`) is a single per-viewer set
  with no record of who added an entry. v1 already works around this with source attribution and
  MysticVanish deference.

### Per-viewer blocks

- `ServerSetBlock` (protocol) and `WorldNotificationHandler#sendPacketIfChunkLoaded` exist, so a block
  *packet* can be addressed to one player. Collision and interaction, however, are resolved
  server-side against the shared chunk (`BlockOperations#setBlock`, `ChunkAccessor#performBlockUpdate`).
  A per-viewer packet therefore changes only what the client draws and predicts. An opening shown to
  one client while the server cell stays solid rubber-bands that player. This is exactly the desync
  §9 forbids.
- **Phase 8 conclusion, provisional:** only `VISUAL_BLOCK_OVERRIDE` is a candidate, and only for
  cells whose collision does not change (decorative swaps, or a solid-to-solid change).
  `COLLISION_BLOCK_OVERRIDE` and `INTERACTION_BLOCK_OVERRIDE` are not offered until a per-viewer
  collision path is found in a newer build. This must be re-verified when Phase 8 starts.

### Audio

- Per-player one-shots exist: `SoundUtil#playSoundEvent2dToPlayer` and `#playSoundEvent3dToPlayer`.
- Per-player music and audio state exist: `EncounterAudioState#applyToPlayer` writes
  `ForcedMusicTracker` (music container index) and `AudioStateComponent` axis values on one player
  entity. That is the vanilla precedent for "two players in the same place hear different story
  audio" (§18).
- Asset types `SoundEvent`, `MusicContainer` (`SingleTrackMusicContainer`) and `AmbienceFX` are
  registered, so the generated quest asset pack (§14) targets real asset types.

## 3. Gap matrix

| Spec § | Requirement | Status | Gap / plan | Phase |
|---|---|---|---|---|
| 3.1 | QuestSession / StorySession as the authoritative context | Missing | `narrative.session.QuestSession` + `QuestSessionService`: player- or party-owned, persisted, versioned. | 2 |
| 3.2 | Service boundaries | Partial | v1 concentrates runtime, actions and conditions in `PlayerQuestService` (1.7k lines). New services live under `narrative.*` and are assembled by `NarrativeRuntime`. | 1–2 |
| 4.1 | Namespaced tags, add/remove/toggle/exists, expiry, source metadata, scope-aware | Partial | v1 tags are bare strings with no expiry or provenance. `NamespacedId` + `QuestTagService` + `TagRecord`. | 1 |
| 4.2 | Typed variables, ten scopes, unsupported scopes fail validation | Partial | v1 variables are untyped strings; unknown scopes silently become `PLAYER`. `ValueType` / `QuestValue` / `VariableSchema` / `VariableScope` + `ScopeSupport`. | 1 |
| 4.3 | Composable condition trees (ALL, ANY, NONE, NOT, XOR, AT_LEAST, AT_MOST, EXACTLY) | Partial | v1 has and/or/not. `narrative.condition` with a compiler that diagnoses impossible and contradictory trees. | 1 |
| 4.4 | Typed actions with SUCCESS / SKIPPED / RETRYABLE / TERMINAL results, idempotent transitions | Missing | `narrative.action`: registry, typed results, executor with an idempotency ledger. v1 events stay reachable through `mysticquests:legacy.event`. | 1 |
| 5 | Trigger adapter, normalised events, per-scope logical activation | Partial | `narrative.trigger`: `TriggerActivationService` (global, player, party and session overrides), normalised `TriggerEvent`, `mysticquests:trigger_enabled` condition, objective signals gated on activation. | 3 |
| 5.3 | MysticQuest* trigger effects | Partial | Existing effects kept. Added effects: `puzzle_input`, `puzzle_reset`, `trigger_state`. Added conditions: `trigger_enabled`, `puzzle_input_available`. Signals go through the `mysticquests:signal` action. Media, cutscene, world-state and entity effects follow their phases. | 3, 7+ |
| 6 | Staff bypass, layered visibility reasons, reconciliation | Partial | `VisibilityService.Source` is the start of reason layering. Missing: bypass permissions and command, `STORY_INSTANCE` / `MODERATION` / `SPECTATOR` reasons, reconcile on world change and party change. | 4 |
| 7 | MysticNameTags bridge | Missing | Config flag exists (`integrations.mysticNameTags`) but nothing reads it. | 5 |
| 8 | Story entity instancing (visibility, targeting, damage, interaction, loot) | Partial | Per-viewer entity hiding exists. Targeting prevention is global per player, not owner-scoped. Damage, interaction and loot filters are missing. | 6 |
| 9 | World overlays | Missing, engine-limited | See §2. Visual-only capability, gated by an explicit capability enum. | 8 |
| 10 | Puzzle engine (ten rule types, randomised selection, persistence, outputs) | Missing | `narrative.puzzle`: definitions compiled from packages, deterministic persisted selection, all ten rules, idempotent outputs. | 7 |
| 11–12 | Web Studio, visual graph, validation classes | Partial | In-game Quest Studio exists. Web Studio deliberately not started (§2 of the spec). Validation classes are implemented as `DiagnosticCode`s now, so the Studio can reuse them. | 12 |
| 13–18 | Media, voice, subtitles, cutscenes, dynamic music | Media, voice, subtitles and per-listener music done (Phase 9); cutscene timelines done (Phase 10) | Engine primitives confirmed in §2; see the Phase 9 notes. | 9–10 |
| 19 | MysticIdentity | Missing | `ACCOUNT` scope reports unsupported until an identity provider is bound. | 11 |
| 20 | Integration registry with capability status | Partial | Bridges exist but are wired ad hoc in `MysticQuestsRuntime`. | 5 |
| 21 | Live debugger and audited interventions | Partial | `/mquest debug`, `/mquest state`. A narrative session dump and audited puzzle reset are added. | 13 |
| 22 | Versioned persistence, stable ids, reconnect, transfer, idempotent rewards | Partial | v1 JSON player files are unversioned and rewards are not idempotent. New narrative documents carry `schemaVersion`, go through a migrator that refuses future versions, and record applied transition keys. | 2 |
| 23 | Safe filenames, no path traversal | Partial | `JsonDocumentStore` encodes every id into a safe filename and refuses to resolve outside its root. | 2 |
| 25 | Migration tooling and reports | Partial | No v1 content needs rewriting. v1 quests run unchanged. Narrative content runs any v1 event or condition through the bridge (`mysticquests:legacy.event` / `.condition`), and the `legacy` namespace is always open for imported state. A content migration report is built (Phase 14: `/mquest narrative migrate [export]`, `MigrationReport`): v1 entries that run unchanged, v1 patterns with a safer 2.0 replacement, unreadable files, and quarantined state documents. A bulk converter of v1 player **state** is deliberately not built: copying state would fork it from the v1 store that v1 content still writes, so it waits for the v1 runtime paths it would replace. | 14 |
| 28 | Event-driven evaluation, cached compiled graphs, indexed triggers | Partial | Conditions compile once per reload. Trigger bindings are indexed by volume key. Puzzle inputs are indexed by puzzle and input id. | 1–7 |

## 4. Order of work in this change

Following §27 and §31, the runtime is built bottom-up. The first change built:

1. **Phase 1 — state foundation.** Namespaced ids, typed values, ten scopes with explicit support,
   tag and variable schemas, tag and variable services, condition trees, typed actions, diagnostics.
2. **Phase 2 — session runtime.** Player- and party-owned sessions, versioned documents and
   migration, checkpoints and rollback, restore on join and release on quit, defined party
   join / leave / disband semantics, idempotent transition ledger.
3. **Phase 3 — trigger runtime (logical layer).** Scoped activation, normalised events, the native
   per-player gate condition, and objective-signal gating.
4. **Phase 7 — puzzle engine core.** It depends only on Phases 1–3. The four-of-ten scenario is a
   headline acceptance test (§26.3), so it lands with them rather than after visibility and entities.

The first change delivered those four. Every later phase has since been built in dependency
order, the Web Studio last, after the runtime contracts it uses were stable (§2, §31):

| Phase | State |
|---|---|
| 4 Staff bypass, layered visibility | Done |
| 5 MysticNameTags bridge, integration status | Done (needs MysticNameTags' MysticQuests support) |
| 6 Story entity isolation | Done, including results ownership through `onDeath`; dropped items have no owner in the engine |
| 8 World overlays | Done, as per-viewer entity overlays; per-player blocks are not possible in the engine |
| 9 Dialogue and media | Done, with per-player voice language and subtitles |
| 10 Cutscenes | Done |
| 11 MysticIdentity | Done: player portal pages, Studio sign-in, and the `account` scope |
| 12 Creator Studio | Done (docs/studio.md) |
| 13 Live tooling | Done: in-game debugger and interventions, read-only Live Sessions in the Studio |
| 14 Migration and hardening | Done: migration report, limits and metrics, load tests, release packaging |

Section 5 lists each acceptance item and the tests that cover it.

## 5. Status after this change

Delivered, with tests (`src/test/java/.../narrative`). The authoring and extension guide is
[narrative-runtime.md](narrative-runtime.md).

| Acceptance item (§26.3, §33) | Status | Covered by |
|---|---|---|
| Typed tags, variables, conditions and actions are validated and observable | Done | `NarrativeStateTest`, `ConditionTreeTest`, `ActionExecutionTest`, `/mquest narrative` |
| Four-of-ten puzzle: four persisted inputs per audience, unrelated inputs inert, outputs once | Done | `FourOfTenPuzzleAcceptanceTest` |
| Reconnect with 3/4 keys, restart recovery without duplicate rewards | Done | `FourOfTenPuzzleAcceptanceTest`, `ActionExecutionTest` |
| Per-session trigger disable does not leak to other players | Done | `TriggerActivationTest`, `FourOfTenPuzzleAcceptanceTest` |
| QuestSession and party sessions restore after reconnect; join / leave / disband defined | Done | `QuestSessionServiceTest` |
| Versioned, migratable persistence; newer documents never overwritten | Done | `DocumentPersistenceTest`, `QuestSessionServiceTest` |
| Content version change while a player holds state | Done | `NarrativeStateTest#storedValuesSurviveATypeChangeAcrossReleases` |
| Audited interventions | Done (puzzle reset, trigger state) | `NarrativeContentTest`, `/mquest narrative` |
| Staff bypass: staff observe everyone; quest state and other viewers unchanged | Done (Phase 4) | `VisibilityBypassTest`, `/mq visibility` |
| Layered visibility: MysticQuests never lifts another system's hide | Done for MysticVanish (v1 deference kept; bypass included); reasons shown by `/mq visibility status` | `VisibilityBypassTest` |
| NameTag layering: quest completion lifts only the MysticQuests reason | Done (Phase 5), needs MysticNameTags with `MysticQuestsSupport` | `VisibilityBypassTest#canSeeIsThePresentationAnswerNameplateModsConsult`, `/mq integrations` |
| Integration capability status (§20) | Done (Phase 5) | `/mq integrations` |
| Story boss: only the owning player/party sees, targets, is targeted by, damages or uses it | Done (Phase 6) | `StoryEntityTest`, `StoryEntityIsolationTest` |
| World overlays: per-audience doors, seals and bridges, including where a player can walk | Done (Phase 8), entity overlays | `WorldOverlayTest` |
| Voice/subtitle: lines resolve by id; a missing locale falls back and is diagnosed; a missing sound still shows its subtitle | Done (Phase 9) | `QuestMediaTest` |
| Story audio per audience: two players in one room hear their own session's lines and music | Done (Phase 9) | `QuestMediaTest#twoPlayersInOneRoomHearOnlyTheirOwnStory` |
| Cutscene skip: required tags, state, progression and cleanup are correct after a skip | Done (Phase 10) | `CutsceneTest` |
| Crash recovery for in-progress cutscenes (§22) | Done (Phase 10): finished as skipped when the player returns | `CutsceneTest#aSceneInterruptedByARestartIsFinishedWhenThePlayerReturns` |
| Live debugger: one read-only view of a player's story state, exportable (§21) | Done (Phase 13, in-game part); interventions: puzzle reset/reroll, trigger state, cutscene play/skip, checkpoint rewind, story restart, objective complete/reset/set, voice-line replay, teleport to a quest volume; presentation reconciles itself from state | `NarrativeDebugTest`, `CheckpointRewindTest`, `/mq debug <player> [export]` |
| Existing quests migrate or produce an explicit, actionable report (§25, §33) | Done (Phase 14, content report); v1 player state stays in the v1 store by design | `MigrationReportTest`, `/mquest narrative migrate [export]` |
| My Identity quest views (§19.1): active quests, current step and objectives, history, story milestones | Done (Phase 11, portal provider `integration/mysticidentity`); replay/season history not supported yet | `QuestsPortalProviderTest`, `MilestoneTest`, `/mq integrations` |
| Studio authentication through MysticIdentity (§19.2) | Done (Phase 11/12): OIDC authorization code with PKCE against MysticIdentity's OpenID Provider (`openid identity.read hytale.read`); the linked Hytale profile is the player whose permissions apply; in-game codes stay available | `StudioOidcTest` |
| `account` scope through MysticIdentity | Done: MysticIdentity's status answer and `PlayerIdentity` now carry the identity id (optional on the wire, so older agents and controllers still interoperate); MysticQuests keys account state on it through `integration/mysticidentity/IdentityAccounts`. Degraded scope: unlinked players skip account writes | `AccountScopeTest`; MysticIdentity `AgentContractTest`, `PlayerIdentityRegistryTest` |
| Limits, metrics and slow-operation diagnostics (§28, §29) | Done (Phase 14): session and entity limits, puzzle-input warning, counters, per-type timings, `/mquest narrative stats` | `NarrativeLimitsTest` |
| Load: event-driven evaluation at scale, restart recovery of every session | Done (Phase 14), runtime level over the in-memory store | `NarrativeLoadTest` |
| Release packaging and rollout (§27 Phase 14) | Done: version 2.0.0, `./gradlew releaseBundle` (tested jar, docs, examples), manifest/build version check, rollout and rollback steps in the server guide | `verifyManifestVersion` |
| Web Studio can create, edit and validate projects without raw config for normal workflows (§33) | Done (Phase 12, first increment): embedded Studio, off by default; forms for quests (steps, objectives), dialogue (lines, choices, flow with unreachable-line check), tag and variable schemas; puzzles, overlays, media, speakers and cutscenes as JSON; raw file editor; quest map | `StudioServiceTest`, `StudioHttpServerTest`, docs/studio.md |
| Studio security (§23): server-side authorization per section, CSRF, origin check, secure cookies, path safety, validated publish, append-only release and audit history | Done (Phase 12) | `StudioServiceTest`, `StudioHttpServerTest` |
| Studio version history: drafts, releases, diffs, rollback (§11) | Done (Phase 12): release 0 keeps the pre-Studio content; rollback loads a release into the draft | `StudioServiceTest#publishingIsValidatedRecordedAndCanBeRolledBack` |
| Studio Live Sessions view (§21): online players, a player's full debug snapshot, runtime limits and metrics; polled, never streamed; at most 10 watchers (§28) | Done (Phase 13, web part), read-only; interventions stay in game | `StudioServiceTest#liveStateNeedsItsOwnPermissionAndWatchersAreLimited` |
| Studio audio pipeline (§14): Ogg Vorbis upload kept as master, header checks (channels, rate, length, Opus refused), generated pack `MysticQuests-Generated` with `Common/Sounds` files and `Server/Audio/SoundEvents` events (layout and SoundEvent schema checked against 0.6.8) | Done (Phase 12); loaded at the next restart; no transcoding or loudness analysis (no encoder on the server); music containers not generated | `StudioAudioTest`, `StudioHttpServerTest` |
| Separate development, staging and production targets (§23): release download, import into another server's draft, validated publish there | Done (Phase 12); bundles accept only content files at valid paths | `StudioBundlesTest` |
| Studio reference and testing views (§11): package tree (projects / seasons as nested packages), World page (every trigger volume and story NPC named, with where; single uses flagged), quest simulator (tracker view per step), tag and variable usage counts, locale coverage, integration status | Done (Phase 12), checked in the browser | docs/studio.md |
| Puzzle Studio and Cutscene Studio (§11, §17): puzzle form with in-browser rule simulation (timed and state-machine rules are validated, not simulated); cutscene timeline | Done (Phase 12), checked in the browser against the example packages | docs/studio.md |

### Phase 10 notes: cutscenes

A cutscene is a timeline of ordinary narrative actions placed at times (`steps`), plus `onEnd`,
which runs once however the scene ends. Camera moves are the v1 `setCamera` event, voice and music
are the Phase 9 media actions, NPCs are the entity actions, and quest effects are tags, variables,
signals or any v1 event. There is no separate camera or actor system to keep in step.

The skip contract (§17.1) is one code path. Playing out, a player skip, a staff skip, a new scene
replacing a running one, and recovery after a disconnect or restart all end the same way:

1. Remaining required steps run in order, at once. Steps marked `cosmetic` are dropped.
2. Story audio still queued for the session's audience is dropped.
3. `onEnd` runs.

Each step is its own ledger transition keyed by the play's run id, so nothing runs twice for one
play, whatever order those endings happen in, and a later play of the same scene runs again.

The load-time checks enforce the contract rather than trusting authors:

- A state-changing action (tags, variables, triggers, puzzles, overlays, music, entities, signals,
  cutscenes) cannot be `cosmetic`, because a skip would drop it and skipped players would end in a
  different state.
- A scene that uses `setCamera` must also use it in `onEnd` (`CUTSCENE_NO_RECOVERY`), because a
  skipped or interrupted scene never reaches its last step.
- `music.set` without a music action in `onEnd` warns (`UNTERMINATED_MEDIA`).

Recovery: the run is stored in the session. A scene its player was watching when they left, or when
the server stopped, is finished as skipped when they join. Resuming half-way was rejected: the
camera and audio would restart mid-scene with no context.

A scene advances on the world thread of the player who started it, since its steps may be v1
events about that player. Party members hear its audio through the session audience but do not
drive it; a v1 `setCamera` with `"targets": "party"` moves everyone's camera.

Not built: player input locking beyond `setCamera`'s `locked` flag, and camera paths (the engine
offers camera modes, not keyframed paths). The timeline editor is in the Studio (Phase 12).

### Phase 9 notes: dialogue and media

What the engine offers (verified in `release/0.6.8`):

- One sound to one player: `SoundUtil#playSoundEvent2dToPlayer`, and the `PlaySoundEvent3D` /
  `PlaySoundEventEntity` packets written to that player's connection. Nothing is broadcast, so a
  line is heard by its audience only, even by players standing beside them.
- Per-player music: the `ForcedMusicTracker` component, which `ForcedMusicSystems.Tick` sends when
  its container index changes (the same override `/audio music force` uses). Index 0 hands music
  back to the world's AmbienceFX.
- Sound categories `Voice`, `Music`, `Ambient`, `SFX` and `UI`, so players' volume sliders apply.
- The player's client language: `PlayerRef#getLanguage`.

What it does not offer, and how the runtime copes:

- **No packet stops a sound already playing.** Interruption policies (`queue`, `interrupt`,
  `ignore_new`, `mix`, `replace_same_speaker`) decide what starts next; an interrupted line plays
  out underneath. `duck_existing` is refused at load with the fix: ducking belongs on the
  SoundEvent asset.
- **No playback progress.** Queued lines need an authored `duration`; without one the loader warns
  that lines will overlap.
- **No subtitle HUD.** Subtitles go to chat (default) or the event title (`narrative.subtitles`).
  A translation key (`subtitleKey`) follows the reader's language independently of the voice
  language, as §16 asks.
- **Area audiences** are covered by `spatial: position`, which fades with the SoundEvent's own
  `MaxDistance`; a per-area listener list was not needed.

Design choices:

- Channel state (playing, queued) is presentation and lives in memory. Music is story state: the
  chosen track is stored with the session, player, party or server and resolved per listener in
  the trigger-override order, so it is rebuilt after a reconnect or restart. MysticQuests writes a
  player's forced music only when its own choice changes, so a vanilla encounter that forces boss
  music in between is not overwritten every tick.
- v1 dialogue stays authoritative (choices, conditions, the conversation page). A node gains an
  optional `voice` that plays the narrative voice line to the reader, without a duplicate subtitle,
  following the NPC when the line is `spatial: entity`. A `voice` naming unknown media fails the
  reload.
- Missing audio is a warning at load, not an error: asset packs can load in a different order on a
  development server, and at runtime a line without its sound still shows its subtitle.

Per-player preferences (§16) are built: `/mquest audio voice <locale|auto>` picks the voice
language independently of the client's (subtitles written with a `subtitleKey` stay in the client's
language, so a player can hear one language and read another), and `/mquest audio subtitles <on|off>`;
both are saved with the player (`QuestMediaService#preferences`, `QuestMediaTest`). The Studio audio
pipeline (§14) is built (Phase 12). Not built: `waitForCompletion` as a blocking step (voice lines
already queue per listener, and cutscene steps run at authored times), AudioState axes, and subtitle
size, background and contrast, which the client controls.

### Phase 8 notes: walls per player, verified against the engine

The provisional conclusion in §2 ("visual-only") was wrong in the useful direction, and is replaced
by this:

- **The server does not stop players walking into things on this engine.**
  `PlayerProcessMovementSystem` has its motion-path collision pass off
  (`FIND_MOTION_PATH_TRIGGERS = false`, "broken until the physics rework"), and its position reset is
  commented out. It only records the trigger and damage blocks at the end position. The same holds in
  `0.7.0-pre.3.1`. Player collision is resolved by the client, against what that client has been
  sent.
- **The Hitbox module is entity collision, and it is per viewer by construction.**
  `HitboxCollisionConfig` (`Server/Entity/HitboxCollision/HardCollision.json`, `SoftCollision`,
  `RotatedCollision`) on a `HitboxCollision` component makes an entity solid. No server system reads
  it for movement: its only users are the tracker that sends it to clients, builder tools, deployables
  and falling blocks. It reaches a client only through `HitboxCollisionSystems.EntityTrackerUpdate`,
  for entities in that client's visible set.
- **So a hitbox entity is a wall for exactly the players it is shown to.** Phase 6 already decides
  per viewer which entities are shown. Overlays reuse that. Show the barrier and the player collides;
  hide it and they walk through. The server never disagrees, so nobody rubber-bands.
  `NameplateVisibilitySystem` now also runs before the hitbox tracker update, so a hidden viewer is
  never sent the collision.
- **Authoring:** build the passage open. Place the door, seal or rubble as an entity and give it
  `HardCollision` with the Hitbox editor tool. Declare it under `overlays` with `default: present`
  (a seal that solving opens) or `absent` (a bridge that solving reveals). Switch it per audience
  with `mysticquests:overlay.hide` / `show` / `reset`, scoped like triggers.
- **Limits:**
  - This is exactly as strong as ordinary walls on this engine: a modified client can ignore any
    collision. Story gating that must be cheat-proof also needs a server check, such as a trigger
    volume that sends unauthorised players back.
  - Don't show a barrier to a player who is standing inside it.
  - **Per-player blocks stay unsupported.** `kind: block` is refused at load. A client can be shown a
    block the server doesn't have, but the server's trigger and damage blocks would still follow the
    real world.
  - If a later engine re-enables server-side collision, revisit this note. The overlay API would not
    change, only whether the server agrees.

### Phase 6 notes

- **Ownership** lives in `StoryEntityRegistry` (collection `story-entities`). It persists apart from
  session loading, so a claimed boss stays isolated across a restart even before its owner returns.
  A claim names `uuid:<entity>` or `generation:<stable id>`. Generation claims are re-bound to
  whichever live entity carries that identity, by `EntityIndexSystem`, so a MysticGeneration
  republish never frees a boss into the shared world.
- **Audience:** the owning player, or whoever is in the owning party *now*. A member who leaves
  loses the party's boss at once.
- **Enforcement**, each modelled on the engine's own systems and verified against `release/0.6.8`:
  - **Visibility.** Story entities join `VisibilityService`'s presentation layer, so the existing
    entity and nameplate systems hide them per viewer. Staff in bypass can observe them.
  - **Damage, both directions.** `StoryEntitySystems.DamageFilter` is a `DamageEventSystem` in
    `DamageModule#getFilterDamageGroup` (as `TriggerVolumeRuleSystems.DamageRuleFilter`).
    Projectiles count as their shooter via `Damage.ProjectileSource`.
  - **Interaction.** `UseFilter` vetoes `UseEntityEvent.Pre` (as `NoUseEntity`).
  - **Targeting.** `Targeting` clears out-of-audience players from a story entity's `TargetMemory`.
  - Story entities and ordinary NPCs are not restricted against each other.
- **Content:** `mysticquests:entity.spawn` (MysticGeneration; the NPC is claimed in the same callback
  that reports it, so no unclaimed frame exists), `entity.claim`, `entity.release`,
  `entity.despawn`. Spawn and despawn are permanent ledger steps, so a rollback never doubles a
  boss.
- **Loot and rewards (§8, "which audience owns the results"):** done as results, not items. A claim
  (or spawn) can carry `onDeath` actions, run once in the owning session for the owning audience
  when the entity dies (`hytale/StoryEntityDeathSystem` → `NarrativeRuntime#onStoryEntityDeath`),
  and the claim ends. Kill credit already belongs to the audience, since outsiders cannot hit a story
  entity. Dropped items stay unowned: the engine has only `PreventPickup`, which blocks everyone, so
  story rewards should come from `onDeath`, not drop lists. Covered by `StoryEntityDeathTest`.

### Phase 5 notes

- **The spec's NameTags API does not exist.** §7 assumes `nameTags.hide(viewer, target, reason)` and
  `restoreReason(...)`. MysticNameTags 1.2.9 has neither. It draws glyph nameplates by packet, per
  viewer, and *asks* `MysticVanishSupport.canSee(viewer, subject)` on each refresh (25–500 ms).
  The bridge follows that pull model instead:
  - MysticQuests exposes `MysticQuestsApi#canSee(viewer, target)`. It is the presentation answer:
    it honours staff bypass and reflects only MysticQuests' own hides.
  - MysticNameTags gains `integrations/MysticQuestsSupport` (reflective, inert without MysticQuests)
    and checks it beside the vanish check in `GlyphNameplateManager`. Its `/tags admin doctor` reports
    the hook.
  - A glyph shows only when **both** systems allow it. A quest ending therefore lifts only the
    MysticQuests reason, and a vanish stays in force (§26.3 "NameTag layering"). No reason
    bookkeeping is needed on either side.
  - The engine's own nameplate is still filtered by `NameplateVisibilitySystem`, now on the
    presentation answer.
- **Parties.** MysticRPG exposes no party API (no provider registration, no party ids), so stable
  party ids, and with them party story sessions, come only from MysticGuilds' lifecycle events. A
  MysticRPG-only server gets shared objectives but per-player story sessions. `/mq integrations`
  reports this as `PARTIAL`.
- **`/mq integrations`** lists every optional integration as active, partial, disabled or absent,
  and says what degrades without it. It detects whether the installed MysticNameTags release
  consults MysticQuests at all.

### Phase 4 notes

- **Bypass is presentation only.** A bypassing viewer is shown everything quests hide *from them*.
  The story's records (`isHidden`, `hiddenFrom`, `playerHidden` conditions, the API) are unchanged,
  and no `VisibilityChange` is posted. Only the entity and nameplate systems read the new
  `presentedHiddenFrom` and `isPresentedHidden`. Switching bypass off restores the hides
  immediately.
- **Permissions.** `mysticquests.visibility.bypass` lets staff toggle with `/mq visibility bypass
  [on|off]`. `mysticquests.visibility.bypass.always` holds it on from join, re-read whenever the
  command runs. Losing the permission mid-session leaves bypass on until the staff member turns it
  off, so players never pop out of view unasked.
- **Not lifted:** a MysticVanish hide. Bypass only releases engine entries MysticQuests placed itself.
- **Reconciliation.** The per-tick re-assertion already covers respawn, world change and other
  mods clearing the engine set. Join applies the always permission, and quit forgets bypass. Bypass
  survives a content reload. `/mq` is registered as a short form of `/mquest`.
- **Not done:** a `STORY_INSTANCE` reason for owner-scoped story entities belongs with Phase 6.
  `MODERATION` and `SPECTATOR` would come from bridges that do not exist yet.

Engine-facing code added in this change was checked with the Workshop's `validate_hytale_code_refs`
against `release/0.6.8`: no missing types or methods, and no new uses of deprecated API.

Found in passing, not changed here: the v1 loader validates quest events and conditions against
fixed lists (`QuestContentLoader.EVENT_TYPES` / `CONDITION_TYPES`). It therefore refuses the
`spawnNpc` and `despawnNpc` events, and every type registered through `MysticQuestsRegistry`, even
though the runtime dispatches them and `docs/api.md` documents registered types as usable in
content. The narrative bridge validates against `PlayerQuestService.BUILT_IN_EVENT_TYPES` plus the
registry instead, so it is not affected.
