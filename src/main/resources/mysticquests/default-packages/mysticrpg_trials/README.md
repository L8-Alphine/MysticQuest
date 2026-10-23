# MysticRPG class trials

The six level-10 class advancement trials of MysticRPG (product owner decision Q16: Warrior, Mage,
Ranger, Rogue, Paladin and Vanguard, all Common, entered from Adventurer). MysticQuests 2.1 writes this
package to `mods/MysticQuests/packages/mysticrpg_trials/` the first time it starts. After that it is
yours: MysticQuests never overwrites it, and a deleted copy does not come back (remove its entry from
`mods/MysticQuests/data/bundled-packages.json` for a fresh copy).

## How a trial runs

1. A level-10 Adventurer advances in MysticRPG (`/mrpg class advance <player> <class>`).
2. MysticRPG asks MysticQuests for the trial `mysticrpg:trial/<class>`. The quest whose `trial.id`
   matches starts. Players cannot take these quests from the board.
3. The run ends exactly once:
   - **completed** when every required objective is done, scored if the trial is ranked;
   - **failed** past `timeLimitSeconds`, or on the death after `maxDeaths`;
   - **abandoned** if the player leaves the server, abandons the quest in the Journal, or the server
     stops.
4. MysticRPG records a pass as trial evidence (SQL only) and grants the class.

Trials can be retried; MysticRPG decides when. Results are kept in `mods/MysticQuests/data/trials.json`
by completion id.

## Editing

- Keep each `trial.id` exactly as MysticRPG's class names it, or the class cannot find its trial.
- Objectives are ordinary MysticQuests objectives. Kill targets are NPC role names.
- `optional: true` objectives never hold the trial open; they raise a ranked trial's score.
- Remove `ranking` to make a trial pass/fail only (MysticRPG then treats the pass as unranked).
- Do not add rewards here: a retried trial would pay them again. MysticRPG's class package is the reward.

## Needs tuning in game

The NPC choices, counts, time limits and death limits are first drafts. They assume the roles spawn
near level-10 players on the server's world; servers with custom spawns should point the objectives
at their own encounters or trigger volumes.
