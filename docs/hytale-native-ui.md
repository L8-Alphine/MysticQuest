# Hytale Native UI Notes

MysticQuests ships native Hytale UI assets under `Common/UI/Custom/mysticquests`:

- `Common.ui` / `Theme.ui` — shared theme tokens
- `Pages/` — full-screen pages

HUD documents are the exception: they live in the shared `Common/UI/Custom/Hud/` root beside the base
game's own, with the mod name in the file name so packs cannot collide —
`Common/UI/Custom/Hud/MysticQuestsQuestHud.ui`, appended as `Hud/MysticQuestsQuestHud.ui`. This is
the layout MysticRPG's working HUD uses (`Hud/MysticRPGVitals.ui`, importing
`../MysticRPG/Theme.ui`). A HUD document filed under the mod's own folder does not resolve.

**Every UI document must live under `Common/UI/Custom/`.** The client resolves
`UICommandBuilder.append("…")` paths against that root, so a document stored anywhere else — for
example `Common/UI/HUD/…` — can never be found. That failure is not a build error: it disconnects the
player with "Could not find document … for Custom UI Append command". The base game follows the same
rule, keeping its own HUD documents at `Common/UI/Custom/Hud/`.

`UiMarkupTest` enforces both halves of this: every appended path must resolve to a real file, and no
`.ui` may sit outside the Custom root.

Hytale UI files are not Noesis/XAML. They use the native `.ui` DSL from the main asset pack:

- Imports assign another UI document to a symbol, for example `$Base = "../../Common.ui";`.
- Reusable blocks are declared with `@Name = Group { ... };` and instantiated as `$Base.@Container { ... }`.
- Runtime-addressable elements use `#Id`.
- Java opens a page by appending the root UI path with `UICommandBuilder.append("mysticquests/Pages/JournalPage.ui")`.
- Java changes labels, visibility, and dynamic lists with selectors such as `#ActiveCount.Text` or `#QuestList`.
- Repeated rows can be appended at runtime with `UICommandBuilder.appendInline("#QuestList", "...native ui source...")`.
- Events are bound from Java with `UIEventBuilder` and `CustomUIEventBindingType`, then handled by an `InteractiveCustomUIPage`.

The Java bridge opens a page with:

1. `CommandContext.senderAsPlayerRef()`
2. `Ref.getStore()`
3. `store.getComponent(ref, Player.getComponentType())`
4. `store.getComponent(ref, PlayerRef.getComponentType())`
5. `player.getPageManager().openCustomPage(ref, store, page)`

## Theme tokens

`Common/UI/Custom/mysticquests/Common.ui` is the source of truth for colours and text styles. Any
`.ui` document should import it (`$MQ = "../Common.ui";`) and reference `$MQ.@AccentGold` rather than
inlining a hex value.

Rows built in Java and pushed with `appendInline` are the exception: those fragments are parsed
without the enclosing document's imports, so they cannot resolve `$MQ.@…`. They use the constants in
`ui/MysticQuestsTheme.java`, which mirror `Common.ui` and must be kept in sync with it.

## Surface status

| File | Wired from | Status |
| --- | --- | --- |
| `Pages/JournalPage.ui` | `MysticQuestJournalPage` | Live. `/journal` quest log: Current / Completed / Abandoned tabs, clickable rows, Track and Abandon on Current. Objectives scroll under their step headings. |
| `Pages/QuestMenuPage.ui` | `QuestMenuPage` | Live. `/quest` board: acceptable quests only, with an Accept action. |
| `Pages/ConversationPage.ui` | `ConversationPage` | Live. Up to eight choices. |
| `../Hud/MysticQuestsQuestHud.ui` | `QuestHud` | Tracked quest: whole-quest progress plus the current step's objectives, up to five rows. |
| `Pages/QuestCompletePage.ui` | — | **Design shell only, not wired.** No Java surface opens it. |
| `Pages/ObjectiveToast.ui` | — | **Design shell only, not wired.** Progress feedback currently goes through `QuestNotificationService`. |
| `Pages/AdminPanelPage.ui` | — | **Design shell only, not wired.** Admin operations are command-driven via `/mquest`. |

The three unwired shells are kept deliberately as the layout reference for the surfaces they
describe. They are not dead assets to prune without first deciding whether those surfaces are still
wanted.

## Root cause: one invalid enum value took down every mod's UI

`Pages/QuestStudioPage.ui` line 17 read `HorizontalAlignment: Right`. The enum is
**`Start` / `Center` / `End`** — across the base game and every other mod on the live server those
three account for all 1,340 uses, and `Right` appears nowhere. That one word stopped the client
registering custom UI documents *for every asset pack on the server*, so each join died on whichever
mod's HUD was appended first. For two days that was MysticQuests' own HUD, which is why this looked
like a HUD bug; once the HUD was deferred it became `Hud/MysticRPGVitals.ui`, a document that had not
changed in weeks.

Lessons worth keeping:

- **"Could not find document X" does not mean X is missing.** It means the client has no registered
  document under that name — which is what happens when the pack's document load aborted. The named
  document is just the first one someone tried to append.
- **A bad document is not contained to itself, or even to its own pack.** It is loaded when the pack
  loads, whether or not anything appends it, and it takes the whole custom-UI load with it.
- **An unknown enum value does not degrade.** Nothing warns, nothing renders wrong; the document
  silently fails to load.
- The corpus is the oracle. Unpacking `Assets.zip` and the other mods' documents out of
  `UserData/CachedAssets` gave 754 known-good documents to compare against; every real defect found
  here — the invalid alignment, the `\n` escape — showed up as "ours is the only file in 755 that
  does this".

`UiMarkupTest` now fails the build on a non-`Start/Center/End` alignment and on any escape other than
`\"`.

## How the pack was narrowed down

The path from "the HUD is broken" to that one word was a bisect, not a diagnosis — every theory about
the HUD document itself was wrong. `-PskipUiPack` (no `Common/UI` content) was the baseline that
proved joins work; from there the pack was halved until one line was left.

These probe flags stay in `build.gradle.kts` because this will happen again, and the HUD disables
itself whenever its document is missing from the JAR, so no probe build can disconnect anyone:

| Build | Ships | Question it answers |
| --- | --- | --- |
| `-PskipUiPack` | nothing | baseline: do joins work at all |
| `-PskipUiMarkup` | textures only | is a *texture* the poison |
| `-PskipUiAssets` | documents only | is a *document* the poison |
| `-PskipUiDocuments=A,B` | all but those | which document |

Compare removals with a **restarted client**. One mid-bisect result — "removed `QuestStudioPage.ui`,
still broken" — was wrong because that build still shipped the HUD document, and the client had
already been served the studio page earlier in the same session. It cost half a day of chasing the
HUD.

**No second `UI` segment in a shipped path.** Textures used to live at
`UI/Custom/mysticquests/Assets/UI/{panels,buttons,meters}/…`. Of every pack the live client loaded —
the base game, MysticRPG, MysticEssentials, BetterLootBox, Bestiary, ScuffedHolograms — MysticQuests
was the only one nesting a second `UI` segment under `UI/Custom/`. They are now at
`Assets/panels`, `Assets/buttons`, `Assets/meters`, and `UiMarkupTest` fails the build if one comes
back. (Ruled out along the way: UI textures do *not* have to be multiples of 32 — 138 of the base
game's 196 custom-UI textures are not.)

**The dialect has no backslash-n escape.** `Pages/QuestStudioPage.ui` carried
`Value: "# Named scripting elements\nconditions: {}\n…"` in a `MultilineTextField`. Across 135
base-game documents and 619 shipped by other mods on that server, a literal backslash-n appears zero
times; the base game writes multi-line strings with real line breaks and escapes only `\"`. The
default is now empty — `QuestStudioPage.java` writes the starting source when the page opens — and
`UiMarkupTest` fails the build on any escape other than `\"`.

## Never push a custom HUD on the ready tick

`Failed to apply CustomUI HUD commands (Could not find document … for Custom UI Append command)` is
what the client says when the document is not in its registry *yet* — it is not evidence the file is
missing. On 2026-08-12 the client had the exact document byte-for-byte in
`UserData/CachedAssets` and still reported it missing, while page appends from the same pack resolved
fine once the player was in-game.

The difference is *when* the append arrives. `PlayerReadyEvent` fires while the client is still
working through the connect-time `[AssetUpdate]` stream, before the pack's UI documents are
registered. MysticRPG's HUD survives that window by accident of design — it shows from the
`whenComplete` callback of an async profile load, not from the ready handler. `QuestHudService`
holds the HUD back deliberately: `reconcileAfterJoin` schedules the first push
`ui.hudJoinDelayMillis` after ready (default 3000), and any reconcile triggered in the meantime —
quests started on join fire change events — is skipped, because the scheduled push reads current
state anyway.

## Things that were ruled out, so they are not tried again

`Failed to apply CustomUI HUD commands (Could not find document … for Custom UI Append command)` is
what the client says for *any* document it could not load, not just a missing file. Ruled out by
evidence, in order:

- **The document path.** Four locations were tried; all failed identically, including
  `Custom/Hud/MysticQuestsQuestHud.ui`, which is exactly the layout of MysticRPG's working
  `Hud/MysticRPGVitals.ui`. The document stays in the shared `Custom/Hud/` root because that matches
  the working reference, not because moving it fixed anything.
- **The document contents.** The blob the client cached is byte-identical to the source — diff
  `UserData/CachedAssets/<first 2 hex>/<rest>` against the file, using the hash the client log prints
  beside the path.
- **Template instances re-assigning a template-set property.** Briefly suspected; the base game does
  this in ~130 documents (`TextField ['Anchor']`, `CheckBox ['Anchor']`, `PageOverlay ['Background']`
  among them), so it is ordinary markup.
- **Unknown elements or properties.** Every element type and property name in these documents also
  appears in the base pack or in another mod's shipped documents.

When the disconnect reappears, the client log at `%AppData%/Hytale/UserData/Logs` names the document
and the cached blobs are diffable against the source — that pair tells you within a minute whether
the client has the bytes you think it has. Reconnect with a freshly restarted client when comparing
results.

## Objective view models

UI surfaces receive `service/ObjectiveView` records — `objectiveId`, `displayName`, `current`,
`target`, `state` — not preformatted strings. Do not re-derive state by parsing `displayName`; the
journal used to regex-parse a `"name: 3/5"` string and that is exactly what the typed record
replaces.

Every objective row renders its state with a glyph (`[x]` / `[ ]`) and a numeric counter in addition
to colour, so the surface stays readable in monochrome.

`service/StageView` is the same idea one level up: a quest's objectives grouped into the steps the
author declared, each carrying its position (`STEP 2 OF 4`) and its own progress. Surfaces read
`JournalEntry.currentStage()` rather than deciding for themselves which objectives matter, and
`JournalEntry.grouped()` says whether to draw step furniture at all — a quest with one unnamed step
is simply ungrouped. [Quest Steps](content-format.md#quest-steps) documents the authoring side.

## Lists that grow past their panel

A quest can carry a dozen objectives, and a fixed-height list silently draws them over whatever sits
below it — in the journal, over the Track and Abandon buttons. Scrolling containers are
`LayoutMode: TopScrolling` with `ScrollbarStyle: $Base.@DefaultScrollbarStyle`, and they need a
bounded height to scroll *within*: `FlexWeight: 1` inside a parent that also flexes, so the list
takes what the fixed rows around it leave. `#ObjectiveList` needed `#QuestDetails` to flex before it
could. `UiMarkupTest.journalObjectiveListScrolls` holds all three parts together.

The HUD is not scrollable — it takes no input — so it bounds its list by showing one step at a time
and labelling the remainder (`+ 3 MORE`) instead of truncating in silence.

## Opening a page from an NPC interaction

A conversation NPC does not open its page from Java. `ConversationService.reconcileInteractables`
puts an `Interactions` component on the entity with
`setInteractionId(InteractionType.Use, "MysticQuests_Conversation")`, and the platform resolves that
name through **two** shipped assets:

| Asset | Purpose |
|---|---|
| `Server/Item/RootInteractions/MysticQuests/MysticQuests_Conversation.json` | Names the interaction chain the id refers to. |
| `Server/Item/Interactions/MysticQuests/MysticQuests_Conversation.json` | The interaction itself — what pressing F actually does. |

The second file must select the page supplier registered from Java:

```json
{
  "Type": "OpenCustomUI",
  "Page": {
    "Id": "MysticQuestsConversation"
  }
}
```

`Page.Id` matches the name passed to
`OpenCustomUIInteraction.registerCustomPageSupplier(plugin, …, "MysticQuestsConversation", …)`.
`Id` is the discriminator key because `Page` is a `CodecMapCodec`, whose default key is `Id`;
`Server/Item/Interactions/Tests/OpenCustomUI.json` in the base pack is the shipped example.

**This failed silently once and is worth stating plainly.** The interaction shipped as
`{"Type": "Interrupt"}`, which is a valid interaction that does nothing. Everything downstream
looked correct — the entity became interactable, the client drew "Press F to talk", the binding
matched, the page supplier was registered — and pressing F simply had no effect, with nothing logged
at either end. The interaction hint comes from the `Interactions` component and is drawn whether or
not the interaction behind it does anything, so **a visible prompt is not evidence the interaction
is wired**.
