#!/usr/bin/env python3
"""Builds the launcher icons from the iPhone app's Icon Composer documents.

The iPhone app's icons (tigerduck-app#1026) are Icon Composer documents in its swift/AppIcons/:
layer images on a 1024-point canvas, each placed by icon.json, with a fill behind them. Each one
becomes an adaptive icon here, as mipmap PNGs at every density:

- the background: the fill and the back layers, carried out to the whole 108 dp layer, so no
  launcher's mask, whatever its shape, ever shows an empty corner;
- the foreground: the other layers;
- the monochrome layer for themed icons, which the launcher tints: the art's dark features
  (eyes, stripes, mouth) and, faintly, its mid tones, as the iPhone's tinted icon shows them.

The 1024-point canvas is the 72 dp a launcher's mask shows of an adaptive icon, so the icon shows
what the iPhone's does, cut to the device's shape; the faces sit inside the 66 dp safe zone.

It also writes the legacy icons, `ic_launcher.png` and `ic_launcher_round.png`, of the default
icon (Naihu), and with --previews a sheet per icon of how a launcher shows it: masked as a circle
and as a rounded square, and themed, light and dark.

    python3 tools/app-icons/build_launcher_icons.py <tigerduck-app>/swift/AppIcons [--previews DIR]

Needs Pillow and NumPy. Run it after the iPhone app's icons change, and commit what it writes.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"

CANVAS_PT = 1024  # Icon Composer's canvas, which a launcher's 72 dp shows
LAYER = CANVAS_PT * 108 // 72  # the 108 dp layer at one pixel a point
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

# The launcher name of each document, and the layers of it that make the background.
ICONS = {
    "naihu": ("Naihu", {"01_tiger_hood"}),
    "tigerduck": ("TigerDuck", set()),
    "tigerduck_christmas": ("TigerDuckChristmas", set()),
}
# The white behind Naihu's hood: the hood, carried out, covers it.
DROPPED = {"00_background"}
DEFAULT_ICON = "naihu"


def gradient(fill: dict) -> np.ndarray:
    """The document's linear fill over the whole layer, as straight RGBA floats."""

    def color(value: str) -> np.ndarray:
        return np.array([float(v) for v in value.split(":")[1].split(",")])

    start, stop = (color(c) for c in fill["linear-gradient"])
    begin, end = fill["orientation"]["start"]["y"], fill["orientation"]["stop"]["y"]
    offset = (LAYER - CANVAS_PT) / 2
    y = ((np.arange(LAYER) + 0.5 - offset) / CANVAS_PT - begin) / (end - begin)
    t = np.clip(y, 0, 1)[:, None]
    rows = start[None, :] * (1 - t) + stop[None, :] * t
    return np.repeat(rows[:, None, :], LAYER, axis=1)


def placed(document: Path, layer: dict) -> np.ndarray:
    """One layer drawn where icon.json puts it, as premultiplied RGBA floats."""
    image = Image.open(document / "Assets" / layer["image-name"]).convert("RGBA")
    position = layer.get("position", {})
    scale = position.get("scale", 1.0)
    dx, dy = position.get("translation-in-points", [0, 0])
    width, height = image.size[0] * scale, image.size[1] * scale
    left = LAYER / 2 + dx - width / 2
    top = LAYER / 2 + dy - height / 2
    step = 1 / scale
    drawn = image.transform(
        (LAYER, LAYER), Image.AFFINE, (step, 0, -left * step, 0, step, -top * step), resample=Image.BICUBIC
    )
    return premultiply(np.asarray(drawn).astype(np.float64) / 255)


def premultiply(rgba: np.ndarray) -> np.ndarray:
    out = rgba.copy()
    out[..., :3] *= out[..., 3:]
    return out


def over(top: np.ndarray, bottom: np.ndarray) -> np.ndarray:
    return top + bottom * (1 - top[..., 3:])


def carried_out(art: np.ndarray) -> np.ndarray:
    """[art] over its own edge colors, spread to every pixel it leaves uncovered."""
    known = art[..., 3] > 0.999
    color = np.where(known[..., None], art[..., :3], 0.0)
    while not known.all():
        total = np.zeros_like(color)
        count = np.zeros(known.shape)
        for axis, shift in ((0, 1), (0, -1), (1, 1), (1, -1)):
            total += np.roll(np.where(known[..., None], color, 0.0), shift, axis=axis)
            count += np.roll(known, shift, axis=axis)
        grown = (~known) & (count > 0)
        color[grown] = total[grown] / count[grown][:, None]
        known = known | grown
    spread = np.concatenate([color, np.ones(known.shape + (1,))], axis=-1)
    return over(art, spread)


def layers(name: str, icons: Path) -> dict[str, np.ndarray]:
    """The icon's background, foreground and monochrome layers, 108 dp at one pixel a point."""
    document_name, back = ICONS[name]
    document = icons / f"{document_name}.icon"
    spec = json.loads((document / "icon.json").read_text(encoding="utf-8"))
    empty = np.zeros((LAYER, LAYER, 4))
    background, foreground = empty.copy(), empty.copy()
    fill = spec.get("fill")
    if isinstance(fill, dict):
        background = premultiply(gradient(fill))
    for group in reversed(spec["groups"]):
        for layer in reversed(group["layers"]):
            if layer.get("hidden") or layer["name"] in DROPPED:
                continue
            art = placed(document, layer)
            if layer["name"] in back:
                background = over(art, background)
            else:
                foreground = over(art, foreground)
    background = carried_out(background)
    return {"background": background, "foreground": foreground, "monochrome": monochrome(over(foreground, background))}


def monochrome(full: np.ndarray) -> np.ndarray:
    """The icon for the launcher to tint, as the iPhone's tinted icon: the dark features (eyes,
    stripes, mouth) opaque, the shapes of the mid tones (beak, hood) faint, the light ones clear."""
    luminance = full[..., :3] @ np.array([0.2126, 0.7152, 0.0722])
    features = np.clip((0.6 - luminance) / 0.35, 0, 1)
    shapes = np.clip((0.88 - luminance) / 0.3, 0, 1)
    ink = np.clip(features + 0.35 * shapes, 0, 1)
    out = np.zeros_like(full)
    out[..., 3] = ink
    return out


def image(rgba: np.ndarray, size: int | None = None) -> Image.Image:
    alpha = rgba[..., 3:]
    straight = np.where(alpha > 0, rgba[..., :3] / np.maximum(alpha, 1e-9), 0)
    pixels = np.concatenate([straight, alpha], axis=-1)
    result = Image.fromarray(np.round(np.clip(pixels, 0, 1) * 255).astype(np.uint8), "RGBA")
    return result if size is None else result.resize((size, size), Image.LANCZOS)


def write_png(result: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    opaque = result.getchannel("A").getextrema() == (255, 255)
    (result.convert("RGB") if opaque else result).save(path, optimize=True)


def viewport(rgba: np.ndarray) -> np.ndarray:
    margin = (LAYER - CANVAS_PT) // 2
    return rgba[margin : margin + CANVAS_PT, margin : margin + CANVAS_PT]


def mask(shape: str, size: int) -> Image.Image:
    """A launcher's mask at [size], 4x supersampled: a circle or a rounded square."""
    big = Image.new("L", (size * 4, size * 4), 0)
    draw = ImageDraw.Draw(big)
    if shape == "circle":
        draw.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=size * 4 * 0.22, fill=255)
    return big.resize((size, size), Image.LANCZOS)


def masked(rgba: np.ndarray, shape: str, size: int) -> Image.Image:
    shown = image(viewport(rgba), size)
    shown.putalpha(Image.fromarray(np.minimum(np.asarray(shown.getchannel("A")), np.asarray(mask(shape, size)))))
    return shown


def themed(mono: np.ndarray, back: tuple[int, int, int], ink: tuple[int, int, int], size: int) -> Image.Image:
    alpha = viewport(mono)[..., 3:]
    colors = np.array(back) / 255 * (1 - alpha) + np.array(ink) / 255 * alpha
    full = np.concatenate([colors, np.ones_like(alpha)], axis=-1)
    return masked(np.pad(full, (((LAYER - CANVAS_PT) // 2,) * 2, ((LAYER - CANVAS_PT) // 2,) * 2, (0, 0))), "circle", size)


def write_resources(name: str, parts: dict[str, np.ndarray]) -> None:
    for density, factor in DENSITIES.items():
        for part, rgba in parts.items():
            write_png(image(rgba, round(108 * factor)), RES / f"mipmap-{density}" / f"ic_launcher_{name}_{part}.png")
        if name == DEFAULT_ICON:
            full = over(parts["foreground"], parts["background"])
            size = round(48 * factor)
            inset = round(2 * factor)
            for legacy, shape in (("ic_launcher", "rounded"), ("ic_launcher_round", "circle")):
                icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
                icon.alpha_composite(masked(full, shape, size - 2 * inset), (inset, inset))
                write_png(icon, RES / f"mipmap-{density}" / f"{legacy}.png")


def write_preview(name: str, parts: dict[str, np.ndarray], folder: Path) -> None:
    size, gap = 288, 48
    full = over(parts["foreground"], parts["background"])
    tiles = [
        masked(full, "circle", size),
        masked(full, "rounded", size),
        themed(parts["monochrome"], (0xD8, 0xE2, 0xFF), (0x00, 0x2F, 0x6C), size),
        themed(parts["monochrome"], (0x1B, 0x1B, 0x21), (0xAD, 0xC6, 0xFF), size),
    ]
    sheet = Image.new("RGBA", (len(tiles) * (size + gap) + gap, size + 2 * gap), (0x80, 0x80, 0x80, 255))
    for i, tile in enumerate(tiles):
        sheet.alpha_composite(tile, (gap + i * (size + gap), gap))
    folder.mkdir(parents=True, exist_ok=True)
    sheet.convert("RGB").save(folder / f"{name}.png", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("icons", type=Path, help="the iPhone app's swift/AppIcons folder")
    parser.add_argument("--previews", type=Path, help="also write a preview sheet per icon here")
    args = parser.parse_args()
    for name in ICONS:
        parts = layers(name, args.icons)
        write_resources(name, parts)
        if args.previews:
            write_preview(name, parts, args.previews)
        print(f"built {name}")


if __name__ == "__main__":
    main()
