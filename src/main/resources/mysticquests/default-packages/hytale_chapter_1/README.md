# Hytale Chapter 1: The Goblin Breach

Hytale 0.7's own Chapter 1, the goblin story, as a MysticQuests quest line. MysticQuests writes this
package to `mods/MysticQuests/packages/hytale_chapter_1/` the first time it starts. After that the
package is yours: edit it, rename it or disable it in `package.yml`. MysticQuests never overwrites
it, and if you delete it, it does not come back. The record of what was installed is
`mods/MysticQuests/data/bundled-packages.json`. Remove the package's entry there to get a fresh copy
on the next start.

## The story in the game

These steps are what Hytale's world events, the Forgotten Temple and its goblin lab already do. The
quests follow them; they change nothing in the game.

1. **Pre-temple breaches.** From the world's third day, Hytale opens a goblin breach in the wilds
   30 to 60 blocks from a player every few minutes (`Goblin_Breach_Prelude_Event`). A beam marks it
   ("The Air Hums"). Walking up opens it and a Goblin Scrapper pack comes through. Beating the pack
   seals it ("Breach Secured / The Forgotten Temple beckons"), and the temple gateway shows on the
   map.
2. **The Forgotten Temple.** The temple gateway leads into the `Forgotten_Temple_Goblins` instance,
   where the goblins left a lab. The first use of its **Power Generator** sets the game flag
   `goblin_lab_generator_found` and ends the pre-temple breaches for the whole universe ("Your Echo
   Trembles"). Powering it sets `goblin_lab_power`, which runs the lab's other devices.
3. **The first breach.** The generator's first use also starts `Goblin_Breach_First_Event`: a breach
   portal opens near a player and leads into **the Guttering Hollows** (`Portals/Portals_Goblins`,
   ten minutes per visit). After that, `Goblin_Breach_Perpetual_Event` opens a new breach every
   thirty minutes.
4. **The Gobliterator MkIIII.** The war machine at the end of the Hollows is a three-phase encounter
   (`Goblin_Gobliterator_Encounter`, plus a hard mode). Its death pays every player in the fight the
   Chapter 1 cosmetic reward.

## The quests

| Quest | Starts | Completes when |
| --- | --- | --- |
| The Air Hums | When you spot a goblin breach, or from the quest board | the breach pack is beaten (`goblin_initial_breach_cleared`) |
| The Forgotten Temple | after The Air Hums | you enter the temple, use the Power Generator, and the lab has power |
| Breach in Progress | after The Forgotten Temple; opens a breach portal near you | you step through into the Guttering Hollows |
| Gobliterator MkIIII | after Breach in Progress | you were in the fight when the Gobliterator died |
| Breach Hunter | unlocked when you spot a recurring breach; needs Gobliterator MkIIII done | you enter the Guttering Hollows three times |

Game flags belong to the whole universe, not to one player. Once anyone has woken the generator,
Hytale stops its pre-temple breaches for everyone. So **The Air Hums** summons a breach of its own
for a player who starts the chapter later (`startGoblinBreach`). For the same reason, *Restore power
to the goblin lab* completes at once on a server where the lab already has power.

## What to change

- **Rewards.** None are set: the game already pays the Gobliterator's own rewards. Add `rewards` and
  `rewardText` to any quest.
- **Where the chapter starts.** `the_air_hums` starts on its own when a breach is spotted near the
  player (`startOn`). Remove `startOn` to start it only from the board or a conversation, or add
  `startOnJoin: true`.
- **Group play.** Breaches and the boss credit every player close by. Each player still needs the
  quest active to get credit.

Every type used here (`startGoblinBreach`, `worldEvent`, `worldEventSignal`, `enterInstance`,
`gameFlag`, `encounter`, `bossDefeated`, `startOn` and `unlockOn`) is documented in
`docs/hytale-story.md` in the MysticQuests repository.

## Testing

```text
/mquest player <player> quest reset hytale_chapter_1:the_air_hums Test
/gameflags list
/worldevent list
```

`/goblins reset` is Hytale's own command for resetting Chapter 1 (game flags and world events). It
does not touch MysticQuests progress.
