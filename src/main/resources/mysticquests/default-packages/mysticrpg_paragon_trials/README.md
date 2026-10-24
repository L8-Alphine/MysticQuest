# MysticRPG Paragon trials (provisional content, PO sign-off pending)

The era challenges of Paragon eras 2 to 5 and the short recovery trial (MysticRPG product-owner addendum
of 24 Oct 2026: P-47, P-48). MysticQuests 2.3 writes this package to
`mods/MysticQuests/packages/mysticrpg_paragon_trials/` the first time it starts; after that it is yours
and is never overwritten.

**Status: PROVISIONAL.** The product owner approved the direction (MysticQuests authors these trials;
content owner: the MysticQuests team; Lewai coordinates) but has not signed the content. Every quest says
`trial.signOff: PENDING`, which `MysticQuestsApi.trials().signOff(trialId)` reports, so a release gate can
refuse unsigned content. Change it to `APPROVED` only with the product owner's sign-off.

| Trial id | Quest | Era floor | Start condition |
|---|---|---|---|
| `mysticrpg:paragon_era_2` | Trial of Ascendancy | Paragon 100 | `mysticrpg` `paragon` min 100 |
| `mysticrpg:paragon_era_3` | Trial of Exaltation | Paragon 1,000 | `mysticrpg` `paragon` min 1000 |
| `mysticrpg:paragon_era_4` | Trial of Transcendence | Paragon 3,000 | `mysticrpg` `paragon` min 3000 |
| `mysticrpg:paragon_era_5` | Trial of Apotheosis | Paragon 6,000 | `mysticrpg` `paragon` min 6000 |
| `mysticrpg:paragon_recovery_trial` | Trial of Rekindling | dormant Paragon | `mysticrpg` `paragonDormant` |

## Evidence MysticRPG consumes

MysticRPG starts a trial through `MysticQuestsApi.get().trials().start(player, trialId)` and accepts only
a `COMPLETED` run with a completion receipt (`trials().receipt(completionId)`): trial id, player, realm,
quest version (this package's `version`), evidence id, causal revision and a SHA-256 receipt digest
(`TrialReceipt.verifies()`). With the shared-SQL trial store (`trials.network` in `config.json`) receipts
are network-backed and a run continues on whichever server of the realm the player moves to. Without
evidence there is no era unlock, no ascension and no recovery (fail closed).

## Editing

- Keep each `trial.id` exactly as written.
- Start conditions ask MysticRPG; they deny whenever MysticRPG cannot answer.
- No quest rewards: trials can be retried. MysticRPG pays the era reward or performs the recovery.
- Raise the package `version` when you change objectives: a run started on one version is not continued
  on a server running another (network handoff refuses the mismatch).
