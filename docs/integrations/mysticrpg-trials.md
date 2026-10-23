# Trials for other mods: MysticRPG class advancement

MysticQuests 2.1 provides the **trial check** MysticRPG's class advancement needs (MysticRPG ADR-012,
product owner decisions Q9, Q11 and Q16). MysticRPG asks MysticQuests to run a trial quest for a
player, waits for the result, and records a pass as evidence before it grants the class. MysticQuests
owns the trial and its progress; MysticRPG owns the evidence and the class change.

MysticQuests does not depend on MysticRPG. It publishes a versioned API
(`org.hyzionstudios.mysticquests.api.trial`), and MysticRPG ships the adapter that turns it into its
own `QuestProvider`. This keeps the plugin dependency one way (MysticRPG → MysticQuests), as with
MysticEconomy.

## What ships

- `api/trial/QuestTrials` (contract version 1), reached through `MysticQuestsApi.get().trials()`:
  `questFor`, `start`, `isActive`, `cancel`, `lastResult`, `evidence(completionId)`, `transferHold`,
  `networkBacked`, `message`.
- `api/trial/TrialResult` and `TrialStatus` (`COMPLETED`, `FAILED`, `ABANDONED`, `REFUSED`), and the
  `TrialResolved` event.
- Quest lifecycle events with a completion id (MysticRPG request Q-1), in
  `event/QuestLifecycleEvents`: `QuestStarted`, `QuestCompleted`, `QuestFailed`, `QuestAbandoned`.
- `failQuest` (Q-2), `questStatus` / `hasCompletedQuest` (Q-3) and `hasQuestTag` for MysticRPG's
  QUEST item requirements.
- The `mysticrpg_trials` package: six level-10 trials, one per Common core class (Warrior, Mage,
  Ranger, Rogue, Paladin, Vanguard), installed into `packages/` on first start.
- `MysticQuestsApi.API_VERSION = 2`.

## Completion ids

Every run of every quest has one completion id, carried by all of its lifecycle events and by its
`TrialResult`:

```
UUIDv3("mysticquests:run:" + player + "|" + questId + "|" + startedAt)
```

`startedAt` is saved with the active quest by both storage backends (JSON and SQLite), so the id is
the same after a restart, and a later run of the same quest gets a different one. Consumers key their
idempotency on it: MysticRPG refuses a completion id it has already consumed.

## How a trial run resolves

A run resolves exactly once, from whichever comes first:

| Cause | Status |
|---|---|
| Every required objective done within the limits | `COMPLETED` (scored when the trial is ranked) |
| Completed after `timeLimitSeconds` | `FAILED` |
| Time limit reached while running (checked every 5 s on the player's world thread) | `FAILED` |
| The death after `maxDeaths` | `FAILED` |
| `failQuest` from code | `FAILED` |
| The player disconnects | `ABANDONED` (the quest is abandoned too) |
| The player abandons the quest in the Journal, or staff reset it | `ABANDONED` |
| `cancel` | `ABANDONED` |
| MysticQuests stops | `ABANDONED` |
| The server crashed during the run | `ABANDONED` on the next start |
| Unknown trial, start conditions unmet, MysticQuests stopping | `REFUSED` (no run began) |

The future `start` returns always completes, never exceptionally, so the caller can never wait
forever. The result is saved in `data/trials.json` before the future completes; `evidence` reads it
back by completion id (the newest 5 000 results are kept).

Trial quests cannot be started from the board, by `startQuest` events or by `/mq` start commands:
a run nobody awaits would complete without being reported.

## Ranking (Q11)

Ranking is optional per trial (`trial.ranking`). A ranked completion scores

```
1 - optionalWeight - timeWeight * overPar - deathPenalty * deaths + optionalWeight * optionalShare
```

clamped to `[0, 1]`, where `overPar` is how far past `parTimeSeconds` the run went as a fraction of
par (at most 1), and `optionalShare` the fraction of `optional: true` objectives completed. The result
carries the raw metrics (`elapsedSeconds`, `deaths`, `optionalObjectives`,
`optionalObjectivesCompleted`, `parTimeSeconds`). An unranked completion reports `ranked = false` and
score 1. MysticRPG turns the score into its rank tiers; MysticQuests never decides a class.

## Network storage (Q-12) and transfers

MysticQuests 2.1 has no network storage: quest state is JSON or SQLite on the local server, and
trial state lives in `data/trials.json` beside it. `networkBacked()` therefore returns false, and
`transferHold(player)` returns a reason while the player has a trial in progress. MysticRPG refuses
a cross-server transfer of such a player with that reason (ADR-012 point 3: rejected or held until
synchronization is guaranteed). A player who leaves the server abandons the trial anyway, so the
hold only matters for in-network transfers.

Cross-server trial continuation remains a **release gate for a network-enabled MysticRPG** (product
owner Q9). It needs MysticQuests quest and trial state in shared SQL storage, keyed by the same
completion ids; that is not built.

## Threading

Lifecycle events and `TrialResolved` are posted on the thread that changed the quest, usually the
world thread. The trial service serialises its own state with one lock and completes futures after
releasing it; callers that do I/O on completion (MysticRPG's SQL evidence) hop to their own executor.
