# MysticQuests Assets v1

**Art direction:** Wayfinder's Reliquary — a quest-focused system of midnight-indigo lacquered wood, warm parchment, hammered brass, turquoise route magic, and ember-gold actions.

The pack is built from thirteen original ImageGen painted masters. `source_chroma/` preserves the generated sources; `source_rgba/` contains helper-cleaned masters. Run `tools/build_mysticquests_asset_pack.py` with the bundled Python/Pillow runtime to reproduce production sizes, states, manifests, review sheets, and the curated runtime copy.

Nine-slice guidance: use a 64 px border on 1x `reliquary_panel.png` (128 px at 2x). Button textures use a 28 px 1x border. Meter frame and fill share a 512×48 canvas and are clipped left-to-right by `ProgressBar.Value`.

Typography recommendation: Hytale's readable default sans-serif, bold uppercase only for short section labels. Keep body text mixed-case at 14–18 px. Spacing follows an 8 px base unit, with 16–24 px panel padding and 32 px minimum interactive targets.
