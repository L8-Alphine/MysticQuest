from __future__ import annotations

import csv
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFont


ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "artifacts" / "MysticQuests_Assets_v1"
CHROMA = PACK / "source_chroma"
MASTERS = PACK / "source_rgba"
ICONS = PACK / "icons"
UI = PACK / "ui"
REVIEW = PACK / "REVIEW"
RUNTIME = ROOT / "src" / "main" / "resources" / "Common" / "UI" / "Custom" / "mysticquests" / "Assets"
REMOVE_KEY = Path.home() / ".codex" / "skills" / ".system" / "imagegen" / "scripts" / "remove_chroma_key.py"


SOURCES = {
    "brand": "mysticquests_emblem_chroma.png",
    "quest_board": "quest_board_chroma.png",
    "journal": "journal_chroma.png",
    "conversation": "conversation_chroma.png",
    "tracker": "tracker_chroma.png",
    "panel": "panel_frame_chroma.png",
    "button": "button_frame_chroma.png",
    "complete": "quest_complete_chroma.png",
    "fallback": "missing_content_chroma.png",
    "admin": "admin_chroma.png",
    "reward": "reward_chroma.png",
    "meter_frame": "meter_frame_chroma.png",
    "meter_fill": "meter_fill_chroma.png",
}


PALETTE = [
    ("background_deep", "#090D19", "Screen surround and deepest wells"),
    ("background_soft", "#11182B", "Primary indigo field"),
    ("panel", "#18233B", "Lacquered panel surface"),
    ("panel_raised", "#243353", "Hover and selected surface"),
    ("parchment", "#E8C98A", "Warm readable parchment"),
    ("parchment_dark", "#A77B43", "Parchment shadow and muted detail"),
    ("brass", "#D89A32", "Structural brass"),
    ("ember_gold", "#FFC857", "Primary action and active quest"),
    ("wayfinder", "#39D5D2", "Navigation, focus, and progress"),
    ("wayfinder_dark", "#147F8D", "Focus shadow and meter start"),
    ("success", "#52D58B", "Completed objective"),
    ("danger", "#E55D52", "Destructive action"),
    ("text_primary", "#FFF4D9", "Primary text"),
    ("text_secondary", "#C6D2E5", "Body and secondary text"),
    ("text_muted", "#7E91AD", "Metadata"),
    ("focus_ring", "#79F1EA", "Keyboard/controller focus"),
]


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGBA").save(path, optimize=True)


def alpha_crop(image: Image.Image, padding: int = 8) -> Image.Image:
    image = image.convert("RGBA")
    box = image.getchannel("A").getbbox()
    if box is None:
        raise RuntimeError("Cleaned master contains no visible pixels")
    left, top, right, bottom = box
    return image.crop((
        max(0, left - padding),
        max(0, top - padding),
        min(image.width, right + padding),
        min(image.height, bottom + padding),
    ))


def contain(image: Image.Image, size: tuple[int, int], padding: int = 0) -> Image.Image:
    available = (max(1, size[0] - padding * 2), max(1, size[1] - padding * 2))
    source = alpha_crop(image)
    scale = min(available[0] / source.width, available[1] / source.height)
    resized = source.resize(
        (max(1, round(source.width * scale)), max(1, round(source.height * scale))),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((size[0] - resized.width) // 2, (size[1] - resized.height) // 2))
    return canvas


def clean_masters() -> None:
    if not REMOVE_KEY.is_file():
        raise FileNotFoundError(f"ImageGen chroma helper not found: {REMOVE_KEY}")
    MASTERS.mkdir(parents=True, exist_ok=True)
    for name, filename in SOURCES.items():
        source = CHROMA / filename
        output = MASTERS / f"{name}_master.png"
        if not source.is_file():
            raise FileNotFoundError(f"Missing generated chroma source: {source}")
        subprocess.run([
            sys.executable,
            str(REMOVE_KEY),
            "--input", str(source),
            "--out", str(output),
            "--auto-key", "border",
            "--soft-matte",
            "--transparent-threshold", "12",
            "--opaque-threshold", "220",
            "--despill",
        ], check=True)
        with Image.open(output) as cleaned:
            if cleaned.mode != "RGBA" or cleaned.getpixel((0, 0))[3] != 0:
                raise RuntimeError(f"Chroma cleanup validation failed: {output}")


def export_icons() -> None:
    targets = {
        "brand": (ICONS / "brand" / "mysticquests_emblem.png", 512),
        "quest_board": (ICONS / "navigation" / "quest_board.png", 256),
        "journal": (ICONS / "navigation" / "journal.png", 256),
        "conversation": (ICONS / "navigation" / "conversation.png", 256),
        "tracker": (ICONS / "navigation" / "tracker.png", 256),
        "admin": (ICONS / "navigation" / "admin.png", 256),
        "complete": (ICONS / "status" / "quest_complete.png", 256),
        "reward": (ICONS / "rewards" / "reward.png", 256),
        "fallback": (ICONS / "fallback" / "missing_content.png", 256),
    }
    for name, (path, edge) in targets.items():
        with Image.open(MASTERS / f"{name}_master.png") as master:
            save(contain(master, (edge, edge), max(12, edge // 20)), path)
            if edge == 256:
                save(contain(master, (64, 64), 4), path.with_name(path.stem + "_64.png"))
                save(contain(master, (32, 32), 2), path.with_name(path.stem + "_32.png"))


def tinted_state(image: Image.Image, brightness: float, color: float, overlay: tuple[int, int, int, int] | None = None) -> Image.Image:
    result = ImageEnhance.Brightness(image.convert("RGBA")).enhance(brightness)
    result = ImageEnhance.Color(result).enhance(color)
    if overlay is not None:
        tint = Image.new("RGBA", result.size, overlay)
        tint.putalpha(Image.eval(result.getchannel("A"), lambda value: value * overlay[3] // 255))
        result = Image.alpha_composite(result, tint)
    return result


def export_ui() -> None:
    panels = UI / "panels"
    buttons = UI / "buttons"
    meters = UI / "meters"

    with Image.open(MASTERS / "panel_master.png") as master:
        panel = alpha_crop(master).resize((1024, 768), Image.Resampling.LANCZOS)
        save(panel, panels / "reliquary_panel@2x.png")
        save(panel.resize((512, 384), Image.Resampling.LANCZOS), panels / "reliquary_panel.png")
        dark = tinted_state(panel, 0.60, 0.76, (5, 13, 26, 64))
        save(dark, panels / "reliquary_panel_dark@2x.png")
        save(dark.resize((512, 384), Image.Resampling.LANCZOS), panels / "reliquary_panel_dark.png")

    with Image.open(MASTERS / "button_master.png") as master:
        base = alpha_crop(master).resize((512, 128), Image.Resampling.LANCZOS)
        states = {
            "default": tinted_state(base, 0.84, 0.92),
            "hover": tinted_state(base, 1.10, 1.06, (18, 125, 141, 20)),
            "pressed": tinted_state(base, 0.68, 0.90),
            "focused": tinted_state(base, 1.12, 1.10, (57, 213, 210, 34)),
            "disabled": tinted_state(base, 0.46, 0.15),
            "success": tinted_state(base, 0.90, 0.88, (82, 213, 139, 58)),
            "destructive": tinted_state(base, 0.88, 0.92, (229, 93, 82, 74)),
        }
        for state, image in states.items():
            save(image, buttons / f"wayfinder_{state}@2x.png")
            save(image.resize((256, 64), Image.Resampling.LANCZOS), buttons / f"wayfinder_{state}.png")

    with Image.open(MASTERS / "meter_frame_master.png") as frame_master, Image.open(MASTERS / "meter_fill_master.png") as fill_master:
        frame = alpha_crop(frame_master).resize((1024, 96), Image.Resampling.LANCZOS)
        strip = alpha_crop(fill_master).resize((846, 28), Image.Resampling.LANCZOS)
        fill = Image.new("RGBA", frame.size, (0, 0, 0, 0))
        fill.alpha_composite(strip, (89, 34))
        save(frame, meters / "objective_frame@2x.png")
        save(fill, meters / "objective_fill@2x.png")
        save(frame.resize((512, 48), Image.Resampling.LANCZOS), meters / "objective_frame.png")
        save(fill.resize((512, 48), Image.Resampling.LANCZOS), meters / "objective_fill.png")


def checker(size: tuple[int, int]) -> Image.Image:
    image = Image.new("RGBA", size, "#0A0F1E")
    draw = ImageDraw.Draw(image)
    step = 24
    for y in range(0, size[1], step):
        for x in range(0, size[0], step):
            color = "#111A2D" if (x // step + y // step) % 2 else "#18243B"
            draw.rectangle((x, y, x + step - 1, y + step - 1), fill=color)
    return image


def font(size: int) -> ImageFont.ImageFont:
    for candidate in (Path("C:/Windows/Fonts/seguisb.ttf"), Path("C:/Windows/Fonts/arialbd.ttf")):
        if candidate.is_file():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def contact_sheet(name: str, title: str, paths: list[Path], columns: int, tile: tuple[int, int]) -> None:
    rows = (len(paths) + columns - 1) // columns
    canvas = checker((columns * tile[0], 76 + rows * tile[1]))
    draw = ImageDraw.Draw(canvas)
    draw.text((24, 20), title, fill="#FFF4D9", font=font(24))
    for index, path in enumerate(paths):
        x = (index % columns) * tile[0]
        y = 76 + (index // columns) * tile[1]
        with Image.open(path) as source:
            preview = contain(source, (tile[0] - 32, tile[1] - 52), 8)
        canvas.alpha_composite(preview, (x + 16, y + 4))
        label = path.stem.replace("@2x", "").replace("_", " ")
        draw.text((x + 16, y + tile[1] - 34), label[:30], fill="#C6D2E5", font=font(15))
    save(canvas, REVIEW / name)


def build_review() -> None:
    icon_paths = sorted(path for path in ICONS.rglob("*.png") if not path.stem.endswith(("_32", "_64")))
    contact_sheet("01_icon_system.png", "MysticQuests — Wayfinder's Reliquary Icon System", icon_paths, 5, (230, 230))
    ui_paths = sorted(UI.rglob("*.png"))
    contact_sheet("02_ui_chrome_and_states.png", "Painted UI Chrome, States, Panels and Layered Meters", ui_paths, 3, (420, 220))

    frame = Image.open(UI / "meters" / "objective_frame@2x.png").convert("RGBA")
    fill = Image.open(UI / "meters" / "objective_fill@2x.png").convert("RGBA")
    board = checker((1120, 680))
    draw = ImageDraw.Draw(board)
    draw.text((24, 18), "Objective meter clipping at runtime values", fill="#FFF4D9", font=font(24))
    for row, percent in enumerate((0, 25, 50, 75, 100)):
        clipped = Image.new("RGBA", fill.size, (0, 0, 0, 0))
        width = round(fill.width * percent / 100)
        if width:
            clipped.alpha_composite(fill.crop((0, 0, width, fill.height)), (0, 0))
        composite = Image.alpha_composite(clipped, frame)
        board.alpha_composite(composite, (48, 84 + row * 112))
        draw.text((930, 116 + row * 112), f"{percent}%", fill="#39D5D2", font=font(20))
    save(board, REVIEW / "03_meter_runtime_states.png")


def copy_runtime() -> None:
    if RUNTIME.exists():
        shutil.rmtree(RUNTIME)
    shutil.copytree(ICONS, RUNTIME / "Icons")
    # Flattened on purpose: no other pack nests a "UI" segment inside UI/Custom/, and the
    # client is the only thing that has to parse these paths.
    for group in ("panels", "buttons", "meters"):
        shutil.copytree(UI / group, RUNTIME / group)


def write_metadata() -> None:
    with (PACK / "PALETTE.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("token", "hex", "usage"))
        writer.writerows(PALETTE)

    readme = """# MysticQuests Assets v1

**Art direction:** Wayfinder's Reliquary — a quest-focused system of midnight-indigo lacquered wood, warm parchment, hammered brass, turquoise route magic, and ember-gold actions.

The pack is built from thirteen original ImageGen painted masters. `source_chroma/` preserves the generated sources; `source_rgba/` contains helper-cleaned masters. Run `tools/build_mysticquests_asset_pack.py` with the bundled Python/Pillow runtime to reproduce production sizes, states, manifests, review sheets, and the curated runtime copy.

Nine-slice guidance: use a 64 px border on 1x `reliquary_panel.png` (128 px at 2x). Button textures use a 28 px 1x border. Meter frame and fill share a 512×48 canvas and are clipped left-to-right by `ProgressBar.Value`.

Typography recommendation: Hytale's readable default sans-serif, bold uppercase only for short section labels. Keep body text mixed-case at 14–18 px. Spacing follows an 8 px base unit, with 16–24 px panel padding and 32 px minimum interactive targets.
"""
    (PACK / "README.md").write_text(readme, encoding="utf-8")

    notes = """# Generation Notes

Built-in `imagegen` mode was used for all thirteen painted masters. Each subject was generated on uniform `#FF00FF`, then cleaned with the installed ImageGen `remove_chroma_key.py` helper using border auto-key, soft matte, thresholds 12/220, and despill.

Shared normalized prompt: stylized-concept; Hytale-compatible fantasy game UI production asset; original chunky hand-painted low-poly forms; midnight indigo, parchment, aged brass, turquoise wayfinding magic, ember gold; front-facing; crisp silhouette; generous padding; no text, trademarks, watermark, enclosing square, cast shadow, or background texture.

Subjects: open route journal brand emblem; quest board; closed journal; dialogue lantern with parchment ribbons; tracker compass and waypoint; completion route and laurel; missing compass wedge fallback; administrator ledger/key/seal; reward satchel; nine-slice panel; stateful button plaque; layered meter frame; layered crystalline meter fill.

The brand emblem master was supplied to later calls strictly as a style/material reference. It was not copied as layout or silhouette.
"""
    (PACK / "GENERATION_NOTES.md").write_text(notes, encoding="utf-8")

    coverage = """# Design Coverage

| System | Assets | Runtime status |
| --- | --- | --- |
| Identity | MysticQuests emblem | Integrated in page chrome |
| Navigation | Quest board, journal, dialogue, tracker, admin | Integrated |
| Quest state | Active tracker, complete destination, missing fallback | Integrated |
| Rewards | Guild satchel/token | Quest completion UI |
| Panels | Light and dark reliquary nine-slices | Integrated |
| Controls | Default, hover, pressed, focused, disabled, success, destructive | Integrated theme |
| Progress | Separate objective frame/fill in 1x and 2x | HUD ProgressBar |

Deferred because the mod does not implement them: classes, professions, rarity tiers, equipment slots, combat resources, status-effect families, and skill trees.
"""
    (PACK / "DESIGN_COVERAGE.md").write_text(coverage, encoding="utf-8")

    rows: list[tuple[str, int, int, str]] = []
    for path in sorted(PACK.rglob("*.png")):
        if "source_chroma" in path.parts or "REVIEW" in path.parts:
            continue
        with Image.open(path) as image:
            rows.append((path.relative_to(PACK).as_posix(), image.width, image.height, image.mode))
    with (PACK / "ASSET_MANIFEST.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("path", "width", "height", "mode"))
        writer.writerows(rows)


def main() -> None:
    for directory in (PACK, CHROMA, MASTERS, ICONS, UI, REVIEW):
        directory.mkdir(parents=True, exist_ok=True)
    clean_masters()
    export_icons()
    export_ui()
    build_review()
    copy_runtime()
    write_metadata()
    print(f"Built MysticQuests asset pack at {PACK}")


if __name__ == "__main__":
    main()
