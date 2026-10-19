# Void Bear Hunt

A standalone hard daily MysticQuests package for the Simple Survival server.

- Quest ID: `void_bear_hunt:void_bear_hunt`
- Objective: kill 2 `Bear_Voidtaken` enemies in the Void Dungeon.
- Window: 04:00–16:00 America/New_York each day.
- Rewards: $500, 2 cobalt bars, 8 iron repair kits, 16 ingredient scraps, and 1 rune shard.

## Import

1. Copy the entire `void_bear_hunt` folder to `mods/MysticQuests/packages/`.
2. Confirm `integrations.vaultUnlocked` is `true` in `mods/MysticQuests/config.json` so
   MysticEconomy can receive the money reward through VaultUnlocked.
3. Run `/mquest reload`.
4. Open `/quests` and test `void_bear_hunt:void_bear_hunt`.

The role and reward IDs were verified against Hytale 0.7.x: `Bear_Voidtaken`,
`Ingredient_Bar_Cobalt`, `Tool_Repair_Kit_Iron`, `Ingredient_Scraps`, and
`Ingredient_Rune_Shard`.

The $500 reward is an assumed balance value. Change both `modifyMoney.amount` and `rewardText` if
the server should pay a different amount. Change both schedule timezones together if the server's
daily reset is not based on America/New_York.

MysticQuests schedules act on online players. Staff can reset an offline player after they return:

```text
/mquest player <player> quest reset void_bear_hunt:void_bear_hunt Daily reset
```
