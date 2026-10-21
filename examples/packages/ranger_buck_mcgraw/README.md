# Ranger Buck McGraw

A standalone MysticQuests conversation package for the HyCitizens citizen shown as **Ranger**.
Interacting with that citizen replaces the normal HyCitizens interaction with MysticQuests'
custom dialogue page.

- Conversation ID: `ranger_buck_mcgraw:ranger_buck`
- HyCitizens ID: `f8be1750-f61c-4737-a350-04d535c8bffc`
- Speaker: Ranger Buck McGraw
- Each player receives Buck's full introduction once. That first-meeting state survives reconnects
  and server restarts.
- On later visits, Buck greets the player by name using `%player%` and randomly selects one of five
  return lines. The last choice is remembered per player and excluded from the next selection.
- The **Got any quests for me?** branch opens the server's MysticQuests quest board, so every quest
  currently available to that player appears. This package intentionally does not invent a quest or
  reward that was not part of Buck's supplied dialogue brief.

## Install

1. Copy the entire `ranger_buck_mcgraw` folder to `mods/MysticQuests/packages/`.
2. Confirm `integrations.hyCitizens` is `true` in `mods/MysticQuests/config.json`.
3. Run `/mquest reload`.
4. Check `/mquest hycitizens info f8be1750-f61c-4737-a350-04d535c8bffc`; it should list
   `ranger_buck_mcgraw:ranger_buck` as a matching conversation.
5. Interact with Ranger and choose **Got any quests for me?**, then **Open the quest board.**

If the citizen is recreated and HyCitizens gives it a new ID, update `hyCitizensId` in
`conversations.yml` or run:

```text
/mquest hycitizens bind ranger_buck_mcgraw:ranger_buck <new-id>
```

