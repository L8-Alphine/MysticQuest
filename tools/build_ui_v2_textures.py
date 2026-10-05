"""Generates the MysticQuests 2.0 UI textures (Redesign Bible §4.1-4.2).

Every surface the Bible draws is a rounded, flat card in one of a handful of palette colours. The
Hytale client draws those with nine-slice PatchStyle textures, so this script bakes one small
texture per surface colour and shape rather than relying on runtime tinting.

Each texture is written twice, as name.png and name@2x.png: the client ships only @2x art for its
own UI and looks the suffix up itself, so shipping both means either lookup order finds one.
Borders in Theme.ui are in 1x units; keep RADIUS and the Border values there in step.

usage: python tools/build_ui_v2_textures.py
"""
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "Common", "UI", "Custom", "mysticquests", "Assets", "v2")

# Bible §4.1 palette plus the in-between surface steps the screens need.
VOID = "#0A0D13"
FRAME = "#0E121B"
PANEL = "#161B26"
CARD = "#1B2130"
CARD_HOVER = "#222939"
CARD_PRESSED = "#283044"
LINE = "#2B3344"
SELECTED = "#2A2342"
PURPLE = "#7E5DD3"
PURPLE_HOVER = "#8D6FE0"
PURPLE_PRESSED = "#6A4BBE"
GOLD = "#CCAA58"
CYAN = "#46C6C4"
GREEN = "#66D395"
RED = "#EF6969"
MUTED = "#9AA4B7"
INPUT = "#0F131C"
TRACK = "#2C3445"

SCALE = 4  # supersampling factor for smooth edges


def rgba(hex_colour, alpha=1.0):
    value = hex_colour.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), int(round(alpha * 255)))


def rounded(size, radius, fill=None, ring=None, ring_width=1.0):
    """A rounded rectangle at 1x `size`, rendered at 2x; returns (1x image, 2x image)."""
    images = []
    for factor in (1, 2):
        width, height = size[0] * factor, size[1] * factor
        big = Image.new("RGBA", (width * SCALE, height * SCALE), (0, 0, 0, 0))
        draw = ImageDraw.Draw(big)
        r = radius * factor * SCALE
        box = (0, 0, width * SCALE - 1, height * SCALE - 1)
        if ring is not None:
            draw.rounded_rectangle(box, r, fill=rgba(*ring))
            inset = int(round(ring_width * factor * SCALE))
            inner = (inset, inset, width * SCALE - 1 - inset, height * SCALE - 1 - inset)
            inner_fill = rgba(*fill) if fill is not None else (0, 0, 0, 0)
            # Clear the centre first so a translucent fill does not sit on top of the ring colour.
            draw.rounded_rectangle(inner, max(r - inset, 0), fill=(0, 0, 0, 0))
            if fill is not None:
                draw.rounded_rectangle(inner, max(r - inset, 0), fill=inner_fill)
        elif fill is not None:
            draw.rounded_rectangle(box, r, fill=rgba(*fill))
        images.append(big.resize((width, height), Image.LANCZOS))
    return images


def solid(size, colour):
    return [Image.new("RGBA", (size[0] * f, size[1] * f), rgba(*colour)) for f in (1, 2)]


def save(name, images):
    os.makedirs(OUT, exist_ok=True)
    images[0].save(os.path.join(OUT, name + ".png"))
    images[1].save(os.path.join(OUT, name + "@2x.png"))


# Surfaces: 32x32, radius 12, Border 14.
SURFACE = ((32, 32), 12)
# Buttons: 32x32, radius 8, Border 10.
BUTTON = ((32, 32), 8)
# Pills: 26x26, radius 11, Border 11. A pill 22 tall is fully round; taller ones gain a flat side.
PILL = ((26, 26), 11)
# Inputs: 32x32, radius 6, Border 8.
INPUT_SHAPE = ((32, 32), 6)


def main():
    surface_fills = {
        "Frame": (FRAME, 0.97),
        "Panel": (PANEL, 1.0),
        "Card": (CARD, 1.0),
        "CardHover": (CARD_HOVER, 1.0),
        "CardPressed": (CARD_PRESSED, 1.0),
        "Inset": (VOID, 0.85),
        "HudPanel": (VOID, 0.90),
    }
    for name, fill in surface_fills.items():
        save("Surface" + name, rounded(SURFACE[0], SURFACE[1], fill=fill))

    # Cards with an outline: the selected list card and the category-coloured board cards.
    outlined = {
        "Selected": (SELECTED, PURPLE),
        "Purple": (CARD, PURPLE),
        "Gold": (CARD, GOLD),
        "Cyan": (CARD, CYAN),
        "Green": (CARD, GREEN),
        "Red": (CARD, RED),
        "Neutral": (CARD, LINE),
        "HudPurple": ((VOID, 0.90), PURPLE),
        "HudNeutral": ((VOID, 0.90), "#3A3550"),
    }
    for name, (fill, ring) in outlined.items():
        fill_spec = fill if isinstance(fill, tuple) else (fill, 1.0)
        ring_spec = (ring, 1.0)
        width = 1.5
        save("Outline" + name, rounded(SURFACE[0], SURFACE[1], fill=fill_spec, ring=ring_spec, ring_width=width))

    # Buttons. Primary is narrative purple (one accent at a time, §4.2); secondary is a quiet
    # outlined card; danger is visually separate from both.
    save("ButtonPrimary", rounded(BUTTON[0], BUTTON[1], fill=(PURPLE, 1.0)))
    save("ButtonPrimaryHover", rounded(BUTTON[0], BUTTON[1], fill=(PURPLE_HOVER, 1.0)))
    save("ButtonPrimaryPressed", rounded(BUTTON[0], BUTTON[1], fill=(PURPLE_PRESSED, 1.0)))
    save("ButtonSecondary", rounded(BUTTON[0], BUTTON[1], fill=("#1E2433", 1.0), ring=("#3A4357", 1.0)))
    save("ButtonSecondaryHover", rounded(BUTTON[0], BUTTON[1], fill=("#262D3F", 1.0), ring=("#4A5470", 1.0)))
    save("ButtonSecondaryPressed", rounded(BUTTON[0], BUTTON[1], fill=("#191E2B", 1.0), ring=("#3A4357", 1.0)))
    save("ButtonDanger", rounded(BUTTON[0], BUTTON[1], fill=("#3A1A20", 1.0), ring=(RED, 1.0)))
    save("ButtonDangerHover", rounded(BUTTON[0], BUTTON[1], fill=("#4C2028", 1.0), ring=(RED, 1.0)))
    save("ButtonDangerPressed", rounded(BUTTON[0], BUTTON[1], fill=("#2E151A", 1.0), ring=(RED, 1.0)))
    save("ButtonDisabled", rounded(BUTTON[0], BUTTON[1], fill=("#171B25", 1.0), ring=("#252B38", 1.0)))
    save("ButtonSelected", rounded(BUTTON[0], BUTTON[1], fill=(SELECTED, 1.0), ring=(PURPLE, 1.0)))
    save("ButtonGhostHover", rounded(BUTTON[0], BUTTON[1], fill=("#1E2433", 1.0)))
    save("ButtonNavSelected", rounded(BUTTON[0], BUTTON[1], fill=(SELECTED, 1.0)))

    # Pills: badges and chips.
    pills = {
        "Purple": PURPLE,
        "Gold": GOLD,
        "Cyan": CYAN,
        "Green": GREEN,
        "Red": RED,
        "Neutral": "#2E3547",
        "Track": TRACK,
    }
    for name, colour in pills.items():
        save("Pill" + name, rounded(PILL[0], PILL[1], fill=(colour, 1.0)))
    save("PillOutlineGold", rounded(PILL[0], PILL[1], fill=("#2A2416", 1.0), ring=(GOLD, 1.0)))
    save("PillOutlineMuted", rounded(PILL[0], PILL[1], fill=(CARD, 1.0), ring=("#3A4357", 1.0)))

    # Text inputs.
    save("Input", rounded(INPUT_SHAPE[0], INPUT_SHAPE[1], fill=(INPUT, 1.0), ring=("#323A4D", 1.0)))
    save("InputFocus", rounded(INPUT_SHAPE[0], INPUT_SHAPE[1], fill=(INPUT, 1.0), ring=(PURPLE, 1.0)))

    # Meters, the way the client's own @ProgressBar is built: the track and the fill share one canvas
    # size and are stretched to the bar, and the fill is revealed left to right as Value grows.
    meter = ((256, 8), 4)
    save("MeterTrack", rounded(meter[0], meter[1], fill=(TRACK, 1.0)))
    for name, colour in {"Gold": GOLD, "Cyan": CYAN, "Purple": PURPLE, "Green": GREEN, "Red": RED}.items():
        save("Meter" + name, rounded(meter[0], meter[1], fill=(colour, 1.0)))
        save("Fill" + name, solid((8, 8), (colour, 1.0)))

    # Thin rules.
    save("Rule", solid((4, 4), (LINE, 1.0)))


if __name__ == "__main__":
    main()
    print("wrote", len([n for n in os.listdir(OUT) if n.endswith(".png")]), "textures to", os.path.normpath(OUT))
