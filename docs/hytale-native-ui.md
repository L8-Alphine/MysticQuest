# Hytale Native UI Notes

MysticQuests V1 ships native Hytale UI assets under:

`Common/UI/Custom/mysticquests`

Hytale UI files are not Noesis/XAML. They use the native `.ui` DSL from the main asset pack:

- Imports assign another UI document to a symbol, for example `$Base = "../../Common.ui";`.
- Reusable blocks are declared with `@Name = Group { ... };` and instantiated as `$Base.@Container { ... }`.
- Runtime-addressable elements use `#Id`.
- Java opens a page by appending the root UI path with `UICommandBuilder.append("mysticquests/Pages/JournalPage.ui")`.
- Java changes labels, visibility, and dynamic lists with selectors such as `#ActiveCount.Text` or `#QuestList`.
- Repeated rows can be appended at runtime with `UICommandBuilder.appendInline("#QuestList", "...native ui source...")`.
- Events are bound from Java with `UIEventBuilder` and `CustomUIEventBindingType`, then handled by an `InteractiveCustomUIPage`.

The current Java bridge opens the journal with:

1. `CommandContext.senderAsPlayerRef()`
2. `Ref.getStore()`
3. `store.getComponent(ref, Player.getComponentType())`
4. `store.getComponent(ref, PlayerRef.getComponentType())`
5. `player.getPageManager().openCustomPage(ref, store, page)`

Translated Noesis screens:

- `Pages/JournalPage.ui`: live `/mquest journal` page.
- `Pages/QuestHud.ui`: native HUD shell for active quest tracking.
- `Pages/ConversationPage.ui`: bottom-anchored dialogue shell.
- `Pages/QuestCompletePage.ui`: completion modal shell.
- `Pages/QuestMenuPage.ui`: quest board shell.
- `Pages/ObjectiveToast.ui`: objective toast stack shell.
- `Pages/AdminPanelPage.ui`: admin/debug shell.

The first wired page is the journal because it maps cleanly to existing quest state. Quest board actions, conversation choices, completion close buttons, and HUD lifecycle should be expanded as interactive pages/HUDs when the runtime starts sending those events.
