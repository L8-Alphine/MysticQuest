# MysticQuests Showcase Tutorial

Copy this `showcase_tutorial` folder into the server data `packages` directory, then run `/mquest reload`.

The conversation is bound to:

```json
"entity": {
  "uuid": "0300201d-c86b-31a8-8f61-b25496c583fd",
  "type": "ShowcaseGuide",
  "name": "Showcase Guide",
  "interactionHint": "interactionHints.talk",
  "showPrompt": true
}
```

Start the demo by interacting with that non-player entity. The quest showcases:

- Entity-bound conversations.
- Branching choices.
- Dialogue objectives.
- HUD and journal updates.
- Player and entity scoped tags.
- Player and entity scoped variables.
- Native notifications.
- Colored chat direction updates.

MysticQuests automatically marks conversation-bound entities as Hytale `Interactable` for online players on join and after `/mquest reload`, so the standard interaction prompt can appear.

Useful debug commands:

```text
/quests
/journal
/mquest state get player self
/mquest state get entity 0300201d-c86b-31a8-8f61-b25496c583fd
```
