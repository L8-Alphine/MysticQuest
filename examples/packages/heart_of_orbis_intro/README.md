# The Heart of Orbis

A standalone, one-time introductory quest for the Simple Survival server.

- Quest ID: `heart_of_orbis_intro:heart_of_orbis`
- Starts automatically the first time a player is ready in the world.
- Remains active across later logins until completed.
- Completes when the player interacts with `Bench_Memories`.
- Sets the player tag `heart_of_orbis_completed`; join-start checks that this tag is absent.
- Rewards $100 through MysticEconomy plus one crude hatchet, pickaxe, shovel, and hoe.
- Never repeats after completion.

## Import

1. Install the accompanying MysticQuests plugin build. Older builds published block coordinates
   instead of block asset IDs and cannot detect `Bench_Memories` interactions.
2. Copy the entire `heart_of_orbis_intro` folder to `mods/MysticQuests/packages/`.
3. Confirm `integrations.vaultUnlocked` is `true` in `mods/MysticQuests/config.json` so
   MysticEconomy receives the $100 reward through VaultUnlocked.
4. Restart the server after replacing the plugin JAR, then run `/mquest reload` if you make later
   package-only changes.
5. Test with a player who has never completed `heart_of_orbis_intro:heart_of_orbis`.

The target and reward item IDs were verified against Hytale 0.7.x: `Bench_Memories`,
`Tool_Hatchet_Crude`, `Tool_Pickaxe_Crude`, `Tool_Shovel_Crude`, and `Tool_Hoe_Crude`.

If a test player already completed the quest, reset its state and remove its completion tag before
joining again:

```text
/mquest player <player> quest reset heart_of_orbis_intro:heart_of_orbis Test first login
/mquest state tag player <player> remove heart_of_orbis_completed
```

The route itself is intentionally represented by the final interaction: the player must find the
temple and use its portal to reach the Heart. Add trigger-volume objectives later only if the server
needs to verify the temple entrance and portal as separate steps.
