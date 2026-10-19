# Simple Survival Goblin Dailies

Two straightforward MysticQuests dailies:

- `simple_survival_goblin_dailies:goblin_patrol` — kill 10 camp Goblin Scrappers.
- `simple_survival_goblin_dailies:goblin_ears` — hold 100 Goblin Ears; completion consumes them.

## Import

1. Copy the entire `simple_survival_goblin_dailies` folder to
   `mods/MysticQuests/packages/` on the remote server.
2. Confirm `integrations.vaultUnlocked` is `true` in
   `mods/MysticQuests/config.json`. MysticEconomy supplies the economy through VaultUnlocked.
3. Run `/mquest reload` from the server console or in game.
4. Open `/quests` and test both entries.

The quests reset at **04:00 America/New_York**. Change `time` or `timezone` under `schedules` in
`quests.yml` if the server uses a different daily reset. MysticQuests schedules act on online
players. A player who was offline during the reset can be reset by staff with:

```text
/mquest player <player> quest reset simple_survival_goblin_dailies:goblin_patrol Daily reset
/mquest player <player> quest reset simple_survival_goblin_dailies:goblin_ears Daily reset
```

## Server-specific IDs

MysticQuests uses exact Hytale IDs.

- The hunt currently targets `Goblin_Scrapper`, the common Goblin role used at camps. If the camp
  spawns another role, look at one with `/npc role` and replace the objective's `target`.
- Hytale 0.7.x does not ship an inventory item named Goblin Ear. This package expects the server's
  custom drop item to be `Goblin_Ear`. Replace both `Goblin_Ear` entries if your drop addon uses a
  different item ID. The quest does not create the item or add it to Goblin drop tables.

The fixed rewards are intentionally basic and predictable. Edit the money amounts or the named
food/tool item entries near the top of `quests.yml` to rebalance them.
