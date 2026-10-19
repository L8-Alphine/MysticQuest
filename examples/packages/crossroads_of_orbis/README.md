# Crossroads of Orbis

Four side quests that build on **Endgame & QoL** and **MMO Skill Tree**, to show what MysticQuests'
mirror of those mods offers content (see `docs/integrations/external-quests.md`).

| Quest | Unlocks when | Asks for | Rewards |
|---|---|---|---|
| Echoes Beyond the Temple | Endgame `story_echo` is finished | entering any Endgame dungeon, defeating any Endgame boss | 250 Defense xp |
| Frost Veteran | Endgame `story_frost_wardens` is finished | three Endgame bosses, then the Frost Lich | 500 Swords and 500 Defense xp |
| Apprentice Miner | MMO Mining level 5 | two more Mining levels | 300 Smithing xp |
| Well Rounded | MMO total level 50 | finishing three MMO Skill Tree quests | 200 Acrobatics xp |

## Import

1. Copy the `crossroads_of_orbis` folder to `mods/MysticQuests/packages/`.
2. Run `/mquest reload`.
3. `/mq integrations` should list Endgame & QoL and MMO Skill Tree as active.
4. Open `/quests`; the quests appear once their condition passes.

Without Endgame & QoL or MMO Skill Tree the package still loads: conditions from a missing mod never
pass, so its quests stay locked.

The ids come from the mods themselves: `story_echo` and `story_frost_wardens` from Endgame's
`Server/Endgame/Quests/Story.json`, `Endgame_Frost_Lich` from its boss roles, skill names from
MMO Skill Tree (`MINING`, `SMITHING`, `DEFENSE`…). `/mq debug <player>` shows the ids of a player's
mirrored quests.
