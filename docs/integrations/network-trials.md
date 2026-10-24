# Network trials: the shared-SQL trial store (MysticQuests 2.3)

MysticRPG product-owner addendum of 24 Oct 2026, **R-1**: "MysticQuests shared-SQL trial persistence
required before network release". This is that store. With it, a trial run (a MysticRPG class trial, a
Paragon era challenge, the recovery trial) follows its player across the servers of one realm, one
server runs it at a time, and every resolved run leaves a completion receipt any server of the realm can
read. Without it (the default) trials stay on the server they started on and MysticRPG refuses to
transfer a player during a trial.

## Turning it on

`config.json`, every server of the realm pointing at the same database:

```json
"trials": {
  "network": {
    "enabled": true,
    "jdbcUrl": "jdbc:mariadb://db.example.net:3306/hyzion",
    "username": "mysticquests",
    "passwordEnv": "MQ_TRIALS_DB_PASSWORD",
    "password": "",
    "realmId": "realm:avalon",
    "serverId": "lobby-1",
    "tablePrefix": "mq_",
    "leaseSeconds": 30,
    "handoffSeconds": 120,
    "peerStaleSeconds": 45,
    "migration": "apply"
  }
}
```

| Key | Default | Bounds | Meaning |
|---|---|---|---|
| `enabled` | `false` | | Use the shared store. Off: trials stay on this server. |
| `jdbcUrl` | | `jdbc:mariadb:`, `jdbc:mysql:`, `jdbc:postgresql:`, `jdbc:h2:` | The database. MariaDB Connector/J serves MariaDB and MySQL (`permitMysqlScheme` is added for a `jdbc:mysql:` URL), the PostgreSQL driver PostgreSQL; both are bundled. H2 is for development and tests and is not bundled. |
| `username`, `passwordEnv`, `password` | | | Credentials; `passwordEnv` names an environment variable and wins when set. |
| `realmId` | | `namespace:name`, lower case, at most 64 | Must equal MysticRPG's realm id. Every row is realm-scoped; realms never see each other's runs. |
| `serverId` | `narrative.serverId` | at most 64 | Must equal MysticRPG's server id (MysticRPG checks both and holds transfers on a mismatch). |
| `tablePrefix` | `mq_` | letter + up to 15 letters, digits, `_` | Table name prefix. |
| `leaseSeconds` | 30 | 10-300 | How long a server's claim on a run lasts without renewal. The heartbeat renews it every third. |
| `handoffSeconds` | 120 | 15-1800 | How long a run waits for its player on another server after they leave. |
| `peerStaleSeconds` | 45 | 15-600 | A server whose heartbeat is older is not a valid transfer target. |
| `migration` | `apply` | `apply`, `dry-run` | `dry-run` logs the statements, applies nothing and keeps trials local. |

Invalid settings are listed at start and nothing is clamped: the store is not used and trials stay
local (fail closed). `/mq trials` (permission `mysticquests.command.admin.debug`) shows where trial
state lives, the dialect, realm, server, schema version, live members, runs here, results waiting for
the store, the capability and every problem.

## How a run moves

1. **Start** (`trials().start`): the quest starts here and the run is claimed in the store: a slot
   (`<prefix>trial_slots`, one run per realm, player and trial) and a run row owned by this server with
   a lease. A refused claim (the player already runs that trial elsewhere, or the store is down)
   abandons the new run with the reason.
2. **While it runs**: every heartbeat (lease / 3) renews the lease and saves deaths and objective
   progress; every death is saved at once, so switching servers never resets deaths.
3. **The player leaves** (a transfer, a quit, a server stop): the run is **handed off** instead of
   abandoned. Its progress is saved, its quest leaves this server's quest data without an abandonment
   record, the row becomes `SUSPENDED` with a deadline of `handoffSeconds`, and the future the caller
   holds completes with `ABANDONED` and the reason `the trial continues on another server`
   (metric `handedOff = 1`). That future result is never saved or posted: it is not the run's result.
4. **The player joins a server of the realm**: that server takes the run over (compare-and-set on the
   revision), restores the quest exactly as it was left (same start time, so the **same completion id**),
   and the player is told the trial continues. The time limit keeps counting from the original start.
5. **The run resolves** on whichever server holds it: in one transaction the row is closed (compare-and-set
   on revision and owner), the result is written with its receipt and the slot is freed. Then the caller
   on that server is told and `TrialResolved` is posted there.
6. **Nobody continues it**: past the handoff deadline (or when an owner's lease has been gone longer than
   `handoffSeconds`, a crashed server) any server's heartbeat abandons it, with a receipt.

A server that crashed hands its runs off at its next start. A server whose run another server took over
(its lease ran out, for example while it could not reach the store) is **fenced**: its next write
matches nothing, it drops its copy without a result and tells the player.

A run is never continued on different trial content: when the joining server's package `version`
differs from the run's, the run is abandoned with that reason.

## Fencing and exactly once

Every run row carries `revision`. Each write is `UPDATE ... WHERE revision = <seen>` (and
`owner_server = <me>` for an owner's write) and raises it by one; zero rows updated means another server
changed the run first. The result insert happens in the same transaction as the final run update and the
slot release, and `trial_results.completion_id` is the primary key, so a run has exactly one result. The
receipt's `causalRevision` is the revision the resolution wrote: every claim, checkpoint, handoff,
take-over and the resolution raise it, so a higher revision is causally later.

## Completion receipts (evidence contract)

`trials().receipt(completionId)` returns a `TrialReceipt`: `trialId`, `playerId`, `realmId`, `questId`,
`questVersion` (the package version the run started on), `evidenceId`
(`UUIDv3("mysticquests:trial-evidence:" + realmId + "|" + completionId)`), `completionId`, `status`,
`causalRevision`, `resolvedAt` (milliseconds), `resolvedBy`, `networkBacked` and `receipt`, a SHA-256 over
those fields (`TrialReceipt.digest`, format `mq-trial-receipt-v1`); `verifies()` re-checks it. A server
with the store returns receipts of runs it resolved at once and loads the player's receipts from the
store when they join (newest 200). A standalone server also writes receipts (realm `local` unless
configured, `networkBacked = false`).

If the store refuses a result at resolution (an outage), the caller is told anyway (nobody waits on the
store) but no receipt exists yet; the result is kept pending in `data/trials.json` and written with its
receipt by the next heartbeat. A consumer that needs evidence (MysticRPG) therefore refuses until the
receipt exists.

## Transfers

`trials().transferHold(player, targetServerId)` (contract 2) is empty only when the player has no
trial, or the store is healthy (fresh heartbeat, schema current) and the target is a live member of the
same realm with a fresh heartbeat on the same schema, and the run has been claimed. Otherwise it names
what is missing. The contract-1 form `transferHold(player)` cannot check a target and holds whenever a
trial is active on a network server.

## Release check (for MysticRPG's network gate)

`trials().networkStatus()` returns `TrialNetworkStatus`: `enabled`, `networkBacked`, realm, server,
dialect, schema version, live members, runs here, pending results, problems.
`networkStatus().capabilities()` contains **`mysticquests:trials/shared-sql`**
(`TrialNetworkStatus.SHARED_SQL_CAPABILITY`) exactly while the store is in use and healthy. MysticRPG
publishes it as its `TrialNetworkReadiness` service; its network release gate reads that.

## Schema and migrations

Schema version 1 (`TrialStoreSchema`), tables `<prefix>trial_schema`, `trial_servers`, `trial_runs`,
`trial_slots`, `trial_results`. Applying is idempotent (`CREATE TABLE IF NOT EXISTS`; an index that
already exists is accepted) and recorded in `trial_schema` (component `trials`); two servers starting at
once both succeed. A store at a newer version than the server understands is refused.

- **Dry run**: `"migration": "dry-run"` logs every statement that would run and keeps trials local.
- **Rollback** (manual, drops all network trial state; receipts in it are lost, so export
  `trial_results` first if MysticRPG still needs them as evidence):
  ```sql
  DROP TABLE IF EXISTS mq_trial_results;
  DROP TABLE IF EXISTS mq_trial_slots;
  DROP TABLE IF EXISTS mq_trial_runs;
  DROP TABLE IF EXISTS mq_trial_servers;
  DROP TABLE IF EXISTS mq_trial_schema;
  ```
  then set `trials.network.enabled` to `false`. `TrialStoreSchema.rollback(version)` lists these
  statements; nothing runs them automatically.
- Upgrading from a standalone server needs no data migration: local results stay in `data/trials.json`
  and new runs go to the store.

## Threading

All SQL runs on one daemon thread (`MysticQuests-TrialStore`), never on a world thread and never under
the trial service's lock. Quest state changes (restore, detach, abandon) run on the player's world thread.
On stop the trial service hands every run off and the runtime waits up to 5 s for the store thread.

## Verification status

- UNIT/INTEGRATION VERIFIED on H2 (`NetworkTrialsTest`, two servers on one database): claim and receipt,
  handoff and continuation with the same completion id and carried deaths and progress, fencing, one run
  per slot, transfer holds (unknown, stale, store down), expiry sweep, content version mismatch, pending
  results after an outage, realm isolation, crash handoff, schema apply/dry-run/rollback listing,
  configuration bounds, receipt tampering.
- NOT YET RUN against real MariaDB, MySQL or PostgreSQL (MysticRPG's R-4 infrastructure gate runs those).
- NEEDS HYTALE RUNTIME TEST: a real transfer between two servers with a trial in progress.
